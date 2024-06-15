package nextflow.lsp.services.script

import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeStringUtils
import nextflow.lsp.ast.ASTUtils
import nextflow.lsp.services.CompletionProvider
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import nextflow.script.dsl.Constant
import nextflow.script.dsl.DslScope
import nextflow.script.dsl.Function
import nextflow.script.dsl.ScriptDsl
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
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
            return Either.forLeft(TOPLEVEL_ITEMS)

        final offsetNode = nodeTree.first()

        isIncomplete = false
        final List<CompletionItem> items = []

        if( offsetNode instanceof VariableExpression ) {
            // e.g. "foo "
            //          ^
            final namePrefix = offsetNode.getName()
            log.debug "completion variable -- '${namePrefix}'"
            populateItemsFromScope(offsetNode, namePrefix, items)
        }
        else if( offsetNode instanceof ConstantExpression ) {
            final parentNode = ast.getParent(offsetNode)
            if( parentNode instanceof MethodCallExpression && !parentNode.isImplicitThis() ) {
                // e.g. "foo ()"
                //          ^
                final namePrefix = parentNode.getMethodAsString()
                log.debug "completion method call -- '${namePrefix}'"
                populateMethodsFromObjectScope(parentNode.getObjectExpression(), namePrefix, items)
            }
            else if( parentNode instanceof PropertyExpression ) {
                // e.g. "foo.bar "
                //              ^
                final namePrefix = parentNode.getPropertyAsString()
                log.debug "completion property -- '${namePrefix}'"
                populateItemsFromObjectScope(parentNode.getObjectExpression(), namePrefix, items)
            }
        }
        else if( offsetNode instanceof ConstructorCallExpression ) {
            // e.g. "new Foo ()"
            //              ^
            final namePrefix = offsetNode.getType().getNameWithoutPackage()
            log.debug "completion constructor call -- '${namePrefix}'"
            populateTypes(namePrefix, new HashSet<>(), items)
        }
        else {
            log.debug "completion ${offsetNode.class.simpleName} -- '${offsetNode.getText()}'"
            populateItemsFromScope(offsetNode, '', items)
        }

        return isIncomplete
            ? Either.forRight(new CompletionList(true, items))
            : Either.forLeft(items)
    }

    private void populateFunctionNames(String namePrefix, List<CompletionItem> items) {
        final includeNames = getIncludeNames(uri)
        final localFunctionNodes = ast.getFunctionNodes(uri)
        final addIncludeRange = getAddIncludeRange(uri)

        for( final functionNode : ast.getFunctionNodes() ) {
            final name = functionNode.getName()
            if( !name.startsWith(namePrefix) )
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

    private void populateProcessNames(String namePrefix, List<CompletionItem> items) {
        final includeNames = getIncludeNames(uri)
        final localProcessNodes = ast.getProcessNodes(uri)
        final addIncludeRange = getAddIncludeRange(uri)

        for( final processNode : ast.getProcessNodes() ) {
            final name = processNode.getName()
            if( !name.startsWith(namePrefix) )
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

    private void populateWorkflowNames(String namePrefix, List<CompletionItem> items) {
        final includeNames = getIncludeNames(uri)
        final localWorkflowNodes = ast.getWorkflowNodes(uri)
        final addIncludeRange = getAddIncludeRange(uri)

        for( final workflowNode : ast.getWorkflowNodes() ) {
            final name = workflowNode.getName()
            if( !name || !name.startsWith(namePrefix) )
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

    private void populateItemsFromObjectScope(Expression object, String namePrefix, List<CompletionItem> items) {
        final Set<String> existingNames = []

        final fields = ASTUtils.getFieldsForObjectExpression(object, ast)
        populateItemsFromFields(fields, namePrefix, existingNames, items)

        final methods = ASTUtils.getMethodsForObjectExpression(object, ast)
        populateItemsFromMethods(methods, namePrefix, existingNames, items)
    }

    private void populateMethodsFromObjectScope(Expression object, String namePrefix, List<CompletionItem> items) {
        final Set<String> existingNames = []

        final methods = ASTUtils.getMethodsForObjectExpression(object, ast)
        populateItemsFromMethods(methods, namePrefix, existingNames, items)
    }

    static private final ClassNode DSL_SCOPE_TYPE = new ClassNode(DslScope)
    static private final ClassNode DSL_CONSTANT_TYPE = new ClassNode(Constant)
    static private final ClassNode DSL_FUNCTION_TYPE = new ClassNode(Function)

    private void populateItemsFromFields(List<FieldNode> fields, String namePrefix, Set<String> existingNames, List<CompletionItem> items) {
        for( final field : fields ) {
            final name = field.getName()
            if( !name.startsWith(namePrefix) || existingNames.contains(name) )
                continue
            existingNames.add(name)

            final item = new CompletionItem()
            item.setLabel(field.getName())
            item.setKind(LanguageServerUtils.astNodeToCompletionItemKind(field))

            if( field.getDeclaringClass().implementsAnyInterfaces(DSL_SCOPE_TYPE) ) {
                final annot = field.getAnnotations().find(an -> an.getClassNode() == DSL_CONSTANT_TYPE)
                if( !annot )
                    continue
                final documentation = annot.getMember('value').getText().stripIndent(true).trim()
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation))
            }

            if( Logger.isDebugEnabled() )
                item.setDetail(field.getDeclaringClass().getNameWithoutPackage())

            items.add(item)
        }
    }

    private void populateItemsFromMethods(List<MethodNode> methods, String namePrefix, Set<String> existingNames, List<CompletionItem> items) {
        for( final method : methods ) {
            final name = method.getName()
            if( !name.startsWith(namePrefix) || existingNames.contains(name) )
                continue
            existingNames.add(name)

            final item = new CompletionItem()
            item.setLabel(method.getName())
            item.setKind(LanguageServerUtils.astNodeToCompletionItemKind(method))

            if( method.getDeclaringClass().implementsAnyInterfaces(DSL_SCOPE_TYPE) ) {
                final annot = method.getAnnotations().find(an -> an.getClassNode() == DSL_FUNCTION_TYPE)
                if( !annot )
                    continue
                final documentation = annot.getMember('value').getText().stripIndent(true).trim()
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation))
            }

            if( Logger.isDebugEnabled() )
                item.setDetail(method.getDeclaringClass().getNameWithoutPackage())

            items.add(item)
        }
    }

    private void populateItemsFromScope(ASTNode node, String namePrefix, List<CompletionItem> items) {
        final Set<String> existingNames = []
        ASTNode current = node
        while( current != null ) {
            if( current instanceof BlockStatement )
                populateItemsFromScope0(current.getVariableScope(), namePrefix, existingNames, items)
            current = ast.getParent(current)
        }

        if( namePrefix.length() == 0 )
            isIncomplete = true
        else
            populateTypes(namePrefix, existingNames, items)
    }

    private void populateItemsFromScope0(VariableScope scope, String namePrefix, Set<String> existingNames, List<CompletionItem> items) {
        for( final variable : scope.getDeclaredVariables().values() ) {
            final name = variable.getName()
            if( !name.startsWith(namePrefix) )
                continue
            if( !((ASTNode) variable).getNodeMetaData('access.method') && existingNames.contains(name) )
                continue
            existingNames.add(name)

            final item = new CompletionItem()
            item.setLabel(name)
            item.setKind(LanguageServerUtils.astNodeToCompletionItemKind((ASTNode) variable))
            items.add(item)
        }

        if( scope.isClassScope() ) {
            final cn = scope.getClassScope()
            populateItemsFromFields(cn.getFields(), namePrefix, existingNames, items)
            populateItemsFromMethods(cn.getMethods(), namePrefix, existingNames, items)
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

}
