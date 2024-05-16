package nextflow.lsp.services

import groovy.transform.CompileStatic
import nextflow.lsp.compiler.ASTNodeCache
import nextflow.lsp.compiler.ASTUtils
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import nextflow.lsp.util.Ranges
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.InsertTextMode
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Provide suggestions for an incomplete expression or statement
 * based on available definitions and the surrounding context in
 * the AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 * @author Jordi Deu-Pons <jordi@jordeu.net>
 */
@CompileStatic
class ScriptCompletionProvider implements CompletionProvider {

    private static final List<CompletionItem> NXF_TOPLEVEL_ITEMS
    
    static {
        NXF_TOPLEVEL_ITEMS = [
            [
                'include',
                '''
                Include statement:

                ```groovy
                include { HELLO } from './hello.nf'
                ```

                [Read more](https://nextflow.io/docs/latest/module.html#module-inclusion)
                ''',
                "include { \$1 } from './\$2'"
            ],
            [
                'def',
                '''
                Function definition:

                ```groovy
                def hello(name) {
                    println "Hello $name!"
                }
                ```

                [Read more](https://nextflow.io/docs/latest/script.html#functions)
                ''',
                """
                def \$1(\$2) {
                    \$3
                }
                """
            ],
            [
                'process',
                '''
                Process definition:

                ```groovy
                process HELLO {
                    input: 
                    val message

                    output:
                    stdout

                    script:
                    """
                    echo '$message world!'
                    """
                }
                ```

                [Read more](https://nextflow.io/docs/latest/process.html)
                ''',
                """
                process \$1 {
                    input:
                    \${2|val,path,env,stdin,tuple|} \${3:identifier}

                    output:
                    \${4|val,path,env,stdout,tuple|} \${5:identifier}

                    script:
                    \"\"\"
                    \$6
                    \"\"\"

                    stub:
                    \"\"\"
                    \$7
                    \"\"\"
                }
                """
            ],
            [
                'workflow',
                '''
                Workflow definition:

                ```groovy
                workflow MY_FLOW {
                    take:
                    input

                    main:
                    input | view | output

                    emit:
                    output
                }
                ```

                [Read more](https://nextflow.io/docs/latest/workflow.html)
                ''',
                """
                workflow \$1 {
                    take:
                    \$2

                    main:
                    \$3

                    emit:
                    \$4
                }
                """
            ],
            [
                'workflow <entry>',
                '''
                Entry workflow definition:

                ```groovy
                workflow {
                    Channel.of('Bonjour', 'Ciao', 'Hello', 'Hola') | view { it -> '$it world!' }
                }
                ```

                [Read more](https://nextflow.io/docs/latest/workflow.html)
                ''',
                """
                workflow {
                    \$1
                }
                """
            ],
        ].collect { name, documentation, insertText ->
            final item = new CompletionItem(name)
            item.setKind(CompletionItemKind.Snippet)
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation.stripIndent(true).trim()))
            item.setInsertText(insertText.stripIndent(true).trim())
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            return item
        }
    }

    private static final List<CompletionItem> NXF_PROCESS_DIRECTIVES

    static {
        NXF_PROCESS_DIRECTIVES = [
            [
                'clusterOptions',
                '''
                The `clusterOptions` directive allows the usage of any native configuration option accepted by your cluster submit command. You can use it to request non-standard resources or use settings that are specific to your cluster and not supported out of the box by Nextflow.

                [Read more](https://nextflow.io/docs/latest/process.html#clusteroptions)
                ''',
                'clusterOptions \'$1\''
            ],
            [
                'container',
                '''
                The `container` directive allows you to execute the process script in a container.

                [Read more](https://nextflow.io/docs/latest/process.html#container)
                ''',
                'container \'$1\''
            ],
            [
                'containerOptions',
                '''
                The `containerOptions` directive allows you to specify any container execution option supported by the underlying container engine (ie. Docker, Singularity, etc). This can be useful to provide container settings only for a specific process.

                [Read more](https://nextflow.io/docs/latest/process.html#containeroptions)
                ''',
                'containerOptions \'$1\''
            ],
            [
                'cpus',
                '''
                The `cpus` directive allows you to define the number of (logical) CPUs required by each task.

                [Read more](https://nextflow.io/docs/latest/process.html#cpus)
                ''',
                'cpus $1'
            ],
            [
                'debug',
                '''
                The `debug` directive allows you to print the process standard output to Nextflow\'s standard output, i.e. the console. By default this directive is disabled.

                [Read more](https://nextflow.io/docs/latest/process.html#debug)
                ''',
                'debug true'
            ],
            [
                'errorStrategy',
                '''
                The `errorStrategy` directive allows you to define how an error condition is managed by the process. By default when an error status is returned by the executed script, the process stops immediately. This in turn forces the entire pipeline to terminate.

                [Read more](https://nextflow.io/docs/latest/process.html#errorstrategy)
                ''',
                'errorStrategy \'$1\''
            ],
            [
                'maxErrors',
                '''
                The `maxErrors` directive allows you to specify the maximum number of times a process can fail when using the `retry` or `ignore` error strategy. By default this directive is disabled.

                [Read more](https://nextflow.io/docs/latest/process.html#maxerrors)
                ''',
                'maxErrors $1'
            ],
            [
                'maxRetries',
                '''
                The `maxRetries` directive allows you to define the maximum number of times a task can be retried when using the `retry` error strategy. By default only one retry is allowed.

                [Read more](https://nextflow.io/docs/latest/process.html#maxretries)
                ''',
                'maxRetries $1'
            ],
            [
                'memory',
                '''
                The `memory` directive allows you to define how much memory is required by each task. Can be a string (e.g. `\'8 GB\'`) or a memory unit (e.g. `8.GB`).

                [Read more](https://nextflow.io/docs/latest/process.html#memory)
                ''',
                'memory $1'
            ],
            [
                'tag',
                '''
                The `tag` directive allows you to associate each process execution with a custom label, so that it will be easier to identify in the log file or in a report.

                [Read more](https://nextflow.io/docs/latest/process.html#tag)
                ''',
                'tag \'$1\''
            ]
        ].collect { name, documentation, insertText ->
            final item = new CompletionItem(name)
            item.setKind(CompletionItemKind.Method)
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation.stripIndent(true).trim()))
            item.setInsertText(insertText)
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            return item
        }
    }

    private static final List<CompletionItem> NXF_PROCESS_INPUTS

    static {
        NXF_PROCESS_INPUTS = [
            [
                'val',
                '''
                ```groovy
                val <identifier>
                ```
                Declare a variable input. The received value can be any type, and it will be made available to the process body (i.e. `script`, `shell`, `exec`) as a variable with the given name.
                ''',
                'val ${1:identifier}'
            ],
            [
                'path',
                '''
                ```groovy
                path <identifier | stageName>
                ```
                Declare a file input. The received value should be a file or collection of files.

                The argument can be an identifier or string. If an identifier, the received value will be made available to the process body as a variable. If a string, the received value will be staged into the task directory under the given alias.
                ''',
                'path ${1:identifier}'
            ],
            [
                'env',
                '''
                ```groovy
                env <identifier>
                ```
                Declare an environment variable input. The received value should be a string, and it will be exported to the task environment as an environment variable given by `identifier`.
                ''',
                'env ${1:identifier}'
            ],
            [
                'stdin',
                '''
                ```groovy
                stdin
                ```
                Declare a `stdin` input. The received value should be a string, and it will be provided as the standard input (i.e. `stdin`) to the task script. It should be declared only once for a process.
                ''',
                'stdin'
            ],
            [
                'tuple',
                '''
                ```groovy
                tuple <arg1>, <arg2>, ...
                ```
                Declare a tuple input. Each argument should be an input declaration such as `val`, `path`, `env`, or `stdin`.

                The received value should be a tuple with the same number of elements as the `tuple` declaration, and each received element should be compatible with the corresponding `tuple` argument. Each tuple element is treated the same way as if it were a standalone input.
                ''',
                'tuple val(${1:identifier}), path(${2:identifier})'
            ],
        ].collect { name, documentation, insertText ->
            final item = new CompletionItem(name)
            item.setKind(CompletionItemKind.Method)
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation.stripIndent(true).trim()))
            item.setInsertText(insertText)
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            return item
        }
    }

    private static final List<CompletionItem> NXF_PROCESS_OUTPUTS

    static {
        NXF_PROCESS_OUTPUTS = [
            [
                'val',
                '''
                ```groovy
                val <value>
                ```
                Declare a variable output. The argument can be any value, and it can reference any output variables defined in the process body (i.e. variables declared without the `def` keyword).
                ''',
                'val ${1:value}'
            ],
            [
                'path',
                '''
                ```groovy
                path <pattern>
                ```
                Declare a file output. It receives the output files from the task environment that match the given pattern.
                ''',
                'path ${1:identifier}'
            ],
            [
                'env',
                '''
                ```groovy
                env <identifier>
                ```
                Declare an environment variable output. It receives the value of the environment variable given by `identifier` from the task environment.
                ''',
                'env ${1:identifier}'
            ],
            [
                'stdout',
                '''
                ```groovy
                stdout
                ```
                Declare a `stdout` output. It receives the standard output of the task script.
                ''',
                'stdout'
            ],
            [
                'eval',
                '''
                ```groovy
                eval <command>
                ```
                Declare an `eval` output. It receives the standard output of the given command, which is executed in the task environment after the task script.
                ''',
                'eval ${1:command}'
            ],
            [
                'tuple',
                '''
                ```groovy
                tuple <arg1>, <arg2>, ...
                ```
                Declare a tuple output. Each argument should be an output declaration such as `val`, `path`, `env`, `stdin`, or `eval`. Each tuple element is treated the same way as if it were a standalone output.
                ''',
                'tuple val(${1:value}), path(${2:pattern})'
            ],
        ].collect { name, documentation, insertText ->
            final item = new CompletionItem(name)
            item.setKind(CompletionItemKind.Method)
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation.stripIndent(true).trim()))
            item.setInsertText(insertText.stripIndent(true).trim())
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            return item
        }
    }

    private static final List<CompletionItem> NXF_OPERATORS

    static {
        NXF_OPERATORS = [
            [
                'filter',
                '''
                The `filter` operator emits the values from a source channel that satisfy a condition, discarding all other values. The filter condition can be a literal value, a regular expression, a type qualifier, or a boolean predicate.

                [Read more](https://nextflow.io/docs/latest/operator.html#filter)
                ''',
                'filter { v -> $1 }'
            ],
            [
                'map',
                '''
                The `map` operator applies a mapping function to each value from a source channel.

                [Read more](https://nextflow.io/docs/latest/operator.html#map)
                ''',
                'map { v -> $1 }'
            ],
            [
                'reduce',
                '''
                The `reduce` operator applies an accumulator function sequentially to each value in a source channel, and emits the final accumulated value. The accumulator function takes two parameters -- the accumulated value and the *i*-th emitted value -- and it should return the accumulated result, which is passed to the next invocation with the *i+1*-th value. This process is repeated for each value in the source channel.

                [Read more](https://nextflow.io/docs/latest/operator.html#reduce)
                ''',
                'reduce { acc, v -> $1 }'
            ],
        ].collect { name, documentation, insertText ->
            final item = new CompletionItem(name)
            item.setKind(CompletionItemKind.Method)
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation.stripIndent(true).trim()))
            item.setInsertText(insertText.stripIndent(true).trim())
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            return item
        }
    }

    private static Logger log = Logger.instance

    private ASTNodeCache ast
    private int maxItemCount = 100
    private boolean isIncomplete = false

    ScriptCompletionProvider(ASTNodeCache ast) {
        this.ast = ast
    }

    @Override
    Either<List<CompletionItem>, CompletionList> provideCompletion(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while peoviding completions")
            return Either.forLeft(Collections.emptyList())
        }

        final uri = URI.create(textDocument.getUri())
        final nodeTree = ast.getNodesAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( !nodeTree )
            // TODO: need to confirm that position is in top level (no leading whitespace? no paren stack?)
            return Either.forLeft(NXF_TOPLEVEL_ITEMS)

        final offsetNode = nodeTree.first()
        final parentNode = ast.getParent(offsetNode)

        isIncomplete = false
        final List<CompletionItem> items = []

        if( nodeTree.any { node -> node instanceof ProcessNode } ) {
            log.debug "completion: populate from process definition"
            populateItemsFromProcess(nodeTree, items)
        }
        else if( nodeTree.any { node -> node instanceof WorkflowNode } ) {
            log.debug "completion: populate from workflow definition"
            populateItemsFromWorkflow(nodeTree, items)
        }
        else if( offsetNode instanceof PropertyExpression ) {
            log.debug "completion: populate from property expression"
            populateItemsFromPropertyExpression(offsetNode, position, items)
        }
        else if( parentNode instanceof PropertyExpression ) {
            log.debug "completion: populate from property expression (parent)"
            populateItemsFromPropertyExpression(parentNode, position, items)
        }
        else if( offsetNode instanceof MethodCallExpression ) {
            log.debug "completion: populate from method call"
            populateItemsFromMethodCallExpression(offsetNode, position, items)
        }
        else if( offsetNode instanceof ConstructorCallExpression ) {
            log.debug "completion: populate from constructor call"
            populateItemsFromConstructorCallExpression(offsetNode, position, items)
        }
        else if( parentNode instanceof MethodCallExpression ) {
            log.debug "completion: populate from method call (parent)"
            populateItemsFromMethodCallExpression(parentNode, position, items)
        }
        else if( offsetNode instanceof VariableExpression ) {
            log.debug "completion: populate from variable"
            populateItemsFromVariableExpression(offsetNode, position, items)
        }
        else if( offsetNode instanceof MethodNode ) {
            log.debug "completion: populate from method"
            populateItemsFromScope(offsetNode, '', items)
        }
        else if( offsetNode instanceof Statement ) {
            log.debug "completion: populate from statement"
            populateItemsFromScope(offsetNode, '', items)
        }

        return isIncomplete
            ? Either.forRight(new CompletionList(true, items))
            : Either.forLeft(items)
    }

    private void populateItemsFromProcess(List<ASTNode> nodeTree, List<CompletionItem> items) {
        String section
        for( final node : nodeTree ) {
            if( node instanceof Statement && node.statementLabels )
                section = node.statementLabels.first()
        }

        final sectionItems = switch( section ) {
            case 'directives' -> NXF_PROCESS_DIRECTIVES
            case 'input' -> NXF_PROCESS_INPUTS
            case 'output' -> NXF_PROCESS_OUTPUTS
            default -> null
        }
        if( sectionItems )
            items.addAll(sectionItems)
    }

    private void populateItemsFromWorkflow(List<ASTNode> nodeTree, List<CompletionItem> items) {
        String section = 'main'
        boolean inClosure = false
        for( final node : nodeTree ) {
            if( node instanceof Statement && node.statementLabels )
                section = node.statementLabels.first()
            if( node instanceof ClosureExpression )
                inClosure = true
        }

        if( section != 'main' )
            return

        // TODO: function, process, workflow names 
        if( !inClosure )
            items.addAll(NXF_OPERATORS)
    }

    private void populateItemsFromPropertyExpression(PropertyExpression propX, Position position, List<CompletionItem> items) {
        final propertyRange = LanguageServerUtils.astNodeToRange(propX.getProperty())
        final propertyName = getMemberName(propX.getPropertyAsString(), propertyRange, position)
        populateItemsFromExpression(propX.getObjectExpression(), propertyName, items)
    }

    private void populateItemsFromMethodCallExpression(MethodCallExpression callX, Position position, List<CompletionItem> items) {
        final methodRange = LanguageServerUtils.astNodeToRange(callX.getMethod())
        final methodName = getMemberName(callX.getMethodAsString(), methodRange, position)
        populateItemsFromExpression(callX.getObjectExpression(), methodName, items)
    }

    private void populateItemsFromConstructorCallExpression(ConstructorCallExpression ctorX, Position position, List<CompletionItem> items) {
        final typeRange = LanguageServerUtils.astNodeToRange(ctorX.getType())
        final typeName = getMemberName(ctorX.getType().getNameWithoutPackage(), typeRange, position)
        populateTypes(ctorX, typeName, new HashSet<>(), items)
    }

    private void populateItemsFromVariableExpression(VariableExpression varX, Position position, List<CompletionItem> items) {
        final varRange = LanguageServerUtils.astNodeToRange(varX)
        final memberName = getMemberName(varX.getName(), varRange, position)
        populateItemsFromScope(varX, memberName, items)
    }

    private void populateItemsFromPropertiesAndFields(List<PropertyNode> properties, List<FieldNode> fields, String memberNamePrefix, Set<String> existingNames, List<CompletionItem> items) {
        for( final property : properties ) {
            final name = property.getName()
            if( !name.startsWith(memberNamePrefix) || existingNames.contains(name) )
                continue
            existingNames.add(name)

            final item = new CompletionItem()
            item.setLabel(property.getName())
            item.setKind(LanguageServerUtils.astNodeToCompletionItemKind(property))
            items.add(item)
        }

        for( final field : fields ) {
            final name = field.getName()
            if( !name.startsWith(memberNamePrefix) || existingNames.contains(name) )
                continue
            existingNames.add(name)

            final item = new CompletionItem()
            item.setLabel(field.getName())
            item.setKind(LanguageServerUtils.astNodeToCompletionItemKind(field))
            items.add(item)
        }
    }

    private void populateItemsFromMethods(List<MethodNode> methods, String memberNamePrefix, Set<String> existingNames, List<CompletionItem> items) {
        for( final method : methods ) {
            final methodName = method.getName()
            if( !methodName.startsWith(memberNamePrefix) || existingNames.contains(methodName) )
                continue
            existingNames.add(methodName)

            final item = new CompletionItem()
            item.setLabel(method.getName())
            item.setKind(LanguageServerUtils.astNodeToCompletionItemKind(method))
            items.add(item)
        }
    }

    private void populateItemsFromExpression(Expression leftSide, String memberNamePrefix, List<CompletionItem> items) {
        final Set<String> existingNames = []

        final properties = ASTUtils.getPropertiesForLeftSideOfPropertyExpression(leftSide, ast)
        final fields = ASTUtils.getFieldsForLeftSideOfPropertyExpression(leftSide, ast)
        populateItemsFromPropertiesAndFields(properties, fields, memberNamePrefix, existingNames, items)

        final methods = ASTUtils.getMethodsForLeftSideOfPropertyExpression(leftSide, ast)
        populateItemsFromMethods(methods, memberNamePrefix, existingNames, items)
    }

    private void populateItemsFromVariableScope(VariableScope variableScope, String memberNamePrefix, Set<String> existingNames, List<CompletionItem> items) {
        for( final variable : variableScope.getDeclaredVariables().values() ) {
            final variableName = variable.getName()
            if( !variableName.startsWith(memberNamePrefix) || existingNames.contains(variableName) )
                continue
            existingNames.add(variableName)

            final item = new CompletionItem()
            item.setLabel(variable.getName())
            item.setKind(LanguageServerUtils.astNodeToCompletionItemKind((ASTNode) variable))
            items.add(item)
        }
    }

    private void populateItemsFromScope(ASTNode node, String namePrefix, List<CompletionItem> items) {
        final Set<String> existingNames = []
        ASTNode current = node
        while( current != null ) {
            if( current instanceof BlockStatement ) {
                populateItemsFromVariableScope(current.getVariableScope(), namePrefix, existingNames, items)
            }
            current = ast.getParent(current)
        }

        if( namePrefix.length() == 0 )
            isIncomplete = true
        else
            populateTypes(node, namePrefix, existingNames, items)
    }

    private static final List<ClassNode> STANDARD_TYPES = [
        ClassHelper.make('java.nio.file.Path'),
        ClassHelper.make('nextflow.Channel'),
        ClassHelper.make('nextflow.util.Duration'),
        ClassHelper.make('nextflow.util.MemoryUnit'),
    ]

    private void populateTypes(ASTNode offsetNode, String namePrefix, Set<String> existingNames, List<CompletionItem> items) {
        // add types defined in the current module
        populateTypes0(ast.getClassNodes(), namePrefix, existingNames, items)

        // add built-in types
        populateTypes0(STANDARD_TYPES, namePrefix, existingNames, items)
    }

    private void populateTypes0(Collection<ClassNode> classNodes, String namePrefix, Set<String> existingNames, List<CompletionItem> items) {
        for( final classNode : classNodes ) {
            if( existingNames.size() >= maxItemCount ) {
                isIncomplete = true
                break
            }
            final classNameWithoutPackage = classNode.getNameWithoutPackage()
            final className = classNode.getName()
            if( !classNameWithoutPackage.startsWith(namePrefix) || existingNames.contains(className) )
                continue
            existingNames.add(className)

            final item = new CompletionItem()
            item.setLabel(classNameWithoutPackage)
            item.setKind(LanguageServerUtils.astNodeToCompletionItemKind(classNode))
            item.setDetail(classNode.getPackageName())
            items.add(item)
        }
    }

    private String getMemberName(String memberName, Range range, Position position) {
        if (position.getLine() == range.getStart().getLine()
                && position.getCharacter() > range.getStart().getCharacter()) {
            int length = position.getCharacter() - range.getStart().getCharacter()
            if (length > 0 && length <= memberName.length())
                return memberName.substring(0, length).trim()
        }
        return ''
    }

}
