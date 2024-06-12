package nextflow.lsp.services.script

import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeStringUtils
import nextflow.lsp.ast.ASTUtils
import nextflow.lsp.services.CompletionProvider
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import nextflow.lsp.util.Ranges
import nextflow.script.dsl.Function
import nextflow.script.dsl.Operator
import nextflow.script.dsl.ProcessDirectiveDsl
import nextflow.script.dsl.ProcessInputDsl
import nextflow.script.dsl.ProcessOutputDsl
import nextflow.script.dsl.ScriptDsl
import nextflow.script.dsl.WorkflowDsl
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.IncompleteNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
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
import org.eclipse.lsp4j.TextEdit
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

    private static final List<CompletionItem> TOPLEVEL_ITEMS
    
    static {
        TOPLEVEL_ITEMS = [
            [
                'shebang',
                '''
                Shebang declaration:

                ```sh
                #!/usr/bin/env nextflow
                ```
                ''',
                '#!/usr/bin/env nextflow\n\n'
            ],
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

    private static final List<CompletionItem> FUNCTIONS = []

    static {
        for( final method : ScriptDsl.getDeclaredMethods() ) {
            final annot = method.getAnnotation(Function)
            if( !annot )
                continue
            final name = method.getName()
            final documentation = annot.value()
            final insertText = "${name}(\${1})"
            final item = new CompletionItem(name)
            item.setKind(CompletionItemKind.Snippet)
            item.setDetail('function')
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation.stripIndent(true).trim()))
            item.setInsertText(insertText)
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            FUNCTIONS << item
        }
    }

    private static final List<CompletionItem> PROCESS_DIRECTIVES = []

    static {
        for( final method : ProcessDirectiveDsl.getDeclaredMethods() ) {
            final annot = method.getAnnotation(Function)
            if( !annot )
                continue
            final name = method.getName()
            final documentation = annot.value()
            final insertText = "${name} \${1}"
            final item = new CompletionItem(name)
            item.setKind(CompletionItemKind.Method)
            item.setDetail('directive')
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation.stripIndent(true).trim()))
            item.setInsertText(insertText)
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            PROCESS_DIRECTIVES << item
        }
    }

    private static final List<CompletionItem> PROCESS_INPUTS = []

    static {
        for( final method : ProcessInputDsl.getDeclaredMethods() ) {
            final annot = method.getAnnotation(Function)
            if( !annot )
                continue
            final name = method.getName()
            final documentation = annot.value()
            final insertText = "${name} \${1}"
            final item = new CompletionItem(name)
            item.setKind(CompletionItemKind.Method)
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation.stripIndent(true).trim()))
            item.setInsertText(insertText)
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            PROCESS_INPUTS << item
        }
    }

    private static final List<CompletionItem> PROCESS_OUTPUTS = []

    static {
        for( final method : ProcessOutputDsl.getDeclaredMethods() ) {
            final annot = method.getAnnotation(Function)
            if( !annot )
                continue
            final name = method.getName()
            final documentation = annot.value()
            final insertText = "${name} \${1}"
            final item = new CompletionItem(name)
            item.setKind(CompletionItemKind.Method)
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation.stripIndent(true).trim()))
            item.setInsertText(insertText.stripIndent(true).trim())
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            PROCESS_OUTPUTS << item
        }
    }

    private static final List<CompletionItem> OPERATORS = []

    static {
        for( final method : WorkflowDsl.getDeclaredMethods() ) {
            final annot = method.getAnnotation(Function)
            if( !annot || !method.isAnnotationPresent(Operator) )
                continue
            final name = method.getName()
            final documentation = annot.value()
            final insertText = "${name} \${1}"
            final item = new CompletionItem(name)
            item.setKind(CompletionItemKind.Method)
            item.setDetail('operator')
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation.stripIndent(true).trim()))
            item.setInsertText(insertText.stripIndent(true).trim())
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            OPERATORS << item
        }
    }

    private static Logger log = Logger.instance

    private ScriptAstCache ast
    private URI uri
    private int maxItemCount = 100
    private boolean isIncomplete = false

    ScriptCompletionProvider(ScriptAstCache ast) {
        this.ast = ast
    }

    @Override
    Either<List<CompletionItem>, CompletionList> completion(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while peoviding completions")
            return Either.forLeft(Collections.emptyList())
        }

        this.uri = URI.create(textDocument.getUri())
        final nodeTree = ast.getNodesAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( !nodeTree )
            return Either.forLeft(Collections.emptyList())

        final offsetNode = nodeTree.first()
        final parentNode = ast.getParent(offsetNode)
        final prefix = offsetNode instanceof VariableExpression ? offsetNode.name : ''

        isIncomplete = false
        final List<CompletionItem> items = []

        for( final node : nodeTree ) {
            if( node instanceof FunctionNode ) {
                log.debug "completion: populate from function definition"
                populateItemsFromFunction(prefix, nodeTree, items)
                break
            }
            if( node instanceof ProcessNode ) {
                log.debug "completion: populate from process definition"
                populateItemsFromProcess(prefix, nodeTree, items)
                break
            }
            if( node instanceof WorkflowNode ) {
                log.debug "completion: populate from workflow definition"
                populateItemsFromWorkflow(prefix, nodeTree, items)
                break
            }
        }

        if( offsetNode instanceof IncompleteNode ) {
            items.addAll(TOPLEVEL_ITEMS)
        }
        else if( offsetNode instanceof PropertyExpression ) {
            // e.g. "foo."
            log.debug "completion: populate from property expression"
            populateItemsFromPropertyExpression(offsetNode, position, items)
        }
        else if( parentNode instanceof PropertyExpression ) {
            // e.g. "foo.bar"
            log.debug "completion: populate from property expression (parent)"
            populateItemsFromPropertyExpression(parentNode, position, items)
        }
        else if( offsetNode instanceof ConstructorCallExpression ) {
            // e.g. "new Foo ()"
            //              ^
            log.debug "completion: populate from constructor call"
            populateItemsFromConstructorCallExpression(offsetNode, position, items)
        }
        else if( parentNode instanceof MethodCallExpression ) {
            // e.g. "foo ()"
            //          ^
            log.debug "completion: populate from method call (parent)"
            populateItemsFromMethodCallExpression(parentNode, position, items)
        }
        else if( offsetNode instanceof VariableExpression ) {
            // e.g. "foo"
            log.debug "completion: populate from variable"
            populateItemsFromVariableExpression(offsetNode, position, items)
        }
        else if( offsetNode instanceof Statement ) {
            log.debug "completion: populate from statement"
            populateItemsFromScope(offsetNode, '', items)
        }

        return isIncomplete
            ? Either.forRight(new CompletionList(true, items))
            : Either.forLeft(items)
    }

    private void populateItemsFromFunction(String prefix, List<ASTNode> nodeTree, List<CompletionItem> items) {
        populateFunctionNames(prefix, items)
    }

    private void populateItemsFromProcess(String prefix, List<ASTNode> nodeTree, List<CompletionItem> items) {
        String section
        for( final node : nodeTree ) {
            if( node instanceof Statement && node.statementLabels )
                section = node.statementLabels.first()
        }

        if( section == 'directives' )
            items.addAll(PROCESS_DIRECTIVES)
        else if( section == 'input' )
            items.addAll(PROCESS_INPUTS)
        else if( section == 'output' )
            items.addAll(PROCESS_OUTPUTS)
        else
            populateFunctionNames(prefix, items)
    }

    private void populateItemsFromWorkflow(String prefix, List<ASTNode> nodeTree, List<CompletionItem> items) {
        String section = 'main'
        boolean inClosure = false
        for( final node : nodeTree ) {
            if( node instanceof Statement && node.statementLabels )
                section = node.statementLabels.first()
            if( node instanceof ClosureExpression )
                inClosure = true
        }

        if( section == 'main' ) {
            populateFunctionNames(prefix, items)
            if( !inClosure ) {
                populateOperatorNames(prefix, items)
                populateProcessNames(prefix, items)
                populateWorkflowNames(prefix, items)
            }
        }

        // TODO: variables in 'take:' section
    }

    private void populateFunctionNames(String prefix, List<CompletionItem> items) {
        for( final item : FUNCTIONS ) {
            final name = item.label
            if( !name.startsWith(prefix) )
                continue

            items.add(item)
        }

        final includeNames = getIncludeNames(uri)
        final localFunctionNodes = ast.getFunctionNodes(uri)
        final addIncludeRange = getAddIncludeRange(uri)

        for( final functionNode : ast.getFunctionNodes() ) {
            final name = functionNode.getName()
            if( !name.startsWith(prefix) )
                continue

            final item = new CompletionItem(name)
            item.setKind(LanguageServerUtils.astNodeToCompletionItemKind(functionNode))
            item.setDetail('function')

            final documentation = ASTNodeStringUtils.getDocumentation(functionNode)
            if( documentation != null )
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation))

            if( functionNode !in localFunctionNodes && name !in includeNames ) {
                final textEdit = createAddIncludeTextEdit(addIncludeRange, uri, name, functionNode)
                item.setAdditionalTextEdits( List.of(textEdit) )
            }

            items.add(item)
        }
    }

    private void populateOperatorNames(String prefix, List<CompletionItem> items) {
        for( final item : OPERATORS ) {
            if( !item.getLabel().startsWith(prefix) )
                continue

            items.add(item)
        }
    }

    private void populateProcessNames(String prefix, List<CompletionItem> items) {
        final includeNames = getIncludeNames(uri)
        final localProcessNodes = ast.getProcessNodes(uri)
        final addIncludeRange = getAddIncludeRange(uri)

        for( final processNode : ast.getProcessNodes() ) {
            final name = processNode.getName()
            if( !name.startsWith(prefix) )
                continue

            final item = new CompletionItem(name)
            item.setKind(LanguageServerUtils.astNodeToCompletionItemKind(processNode))
            item.setDetail('process')

            final documentation = ASTNodeStringUtils.getDocumentation(processNode)
            if( documentation != null )
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation))

            if( processNode !in localProcessNodes && name !in includeNames ) {
                final textEdit = createAddIncludeTextEdit(addIncludeRange, uri, name, processNode)
                item.setAdditionalTextEdits( List.of(textEdit) )
            }

            items.add(item)
        }
    }

    private void populateWorkflowNames(String prefix, List<CompletionItem> items) {
        final includeNames = getIncludeNames(uri)
        final localWorkflowNodes = ast.getWorkflowNodes(uri)
        final addIncludeRange = getAddIncludeRange(uri)

        for( final workflowNode : ast.getWorkflowNodes() ) {
            final name = workflowNode.getName()
            if( !name || !name.startsWith(prefix) )
                continue

            final item = new CompletionItem(name)
            item.setKind(LanguageServerUtils.astNodeToCompletionItemKind(workflowNode))
            item.setDetail('workflow')

            final documentation = ASTNodeStringUtils.getDocumentation(workflowNode)
            if( documentation != null )
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation))

            if( workflowNode !in localWorkflowNodes && name !in includeNames ) {
                final textEdit = createAddIncludeTextEdit(addIncludeRange, uri, name, workflowNode)
                item.setAdditionalTextEdits( List.of(textEdit) )
            }

            items.add(item)
        }
    }

    private List<String> getIncludeNames(URI uri) {
        final List<String> result = []
        for( final node : ast.getIncludeNodes(uri) ) {
            for( final module : node.modules )
                result.add(module.alias ?: module.name)
        }
        return result
    }

    private Range getAddIncludeRange(URI uri) {
        final includeNodes = ast.getIncludeNodes(uri)
        if( !includeNodes )
            return new Range(new Position(1, 0), new Position(1, 0))
        final lastInclude = includeNodes.last()
        final lastIncludeRange = LanguageServerUtils.astNodeToRange(lastInclude)
        final includeLine = lastIncludeRange ? lastIncludeRange.getEnd().getLine() + 1 : 0
        return new Range(new Position(includeLine, 0), new Position(includeLine, 0))
    }

    private TextEdit createAddIncludeTextEdit(Range range, URI uri, String name, ASTNode node) {
        final source = Path.of(uri).getParent().relativize(Path.of(ast.getURI(node)))
        final newText = new StringBuilder()
            .append("include { ")
            .append(name)
            .append(" } from './")
            .append(source.toString())
            .append("'\n")
            .toString()
        return new TextEdit(range, newText)
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
        populateTypes(typeName, new HashSet<>(), items)
    }

    private void populateItemsFromVariableExpression(VariableExpression varX, Position position, List<CompletionItem> items) {
        final varRange = LanguageServerUtils.astNodeToRange(varX)
        final memberName = getMemberName(varX.getName(), varRange, position)
        populateItemsFromScope(varX, memberName, items)
    }

    private void populateItemsFromExpression(Expression leftSide, String memberNamePrefix, List<CompletionItem> items) {
        final Set<String> existingNames = []

        final properties = ASTUtils.getPropertiesForObjectExpression(leftSide, ast)
        populateItemsFromProperties(properties, memberNamePrefix, existingNames, items)

        final fields = ASTUtils.getFieldsForObjectExpression(leftSide, ast)
        populateItemsFromFields(fields, memberNamePrefix, existingNames, items)

        final methods = ASTUtils.getMethodsForObjectExpression(leftSide, ast)
        populateItemsFromMethods(methods, memberNamePrefix, existingNames, items)
    }

    private void populateItemsFromProperties(List<PropertyNode> properties, String memberNamePrefix, Set<String> existingNames, List<CompletionItem> items) {
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
    }

    private void populateItemsFromFields(List<FieldNode> fields, String memberNamePrefix, Set<String> existingNames, List<CompletionItem> items) {
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
            populateTypes(namePrefix, existingNames, items)
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

    private void populateTypes(String namePrefix, Set<String> existingNames, List<CompletionItem> items) {
        // add built-in types
        populateTypes0(ScriptDsl.TYPES, namePrefix, existingNames, items)
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
            items.add(item)
        }
    }

    private String getMemberName(String memberName, Range range, Position position) {
        if( position.getLine() == range.getStart().getLine() && position.getCharacter() > range.getStart().getCharacter() ) {
            final length = position.getCharacter() - range.getStart().getCharacter()
            if( length > 0 && length <= memberName.length() )
                return memberName.substring(0, length).trim()
        }
        return ''
    }

}
