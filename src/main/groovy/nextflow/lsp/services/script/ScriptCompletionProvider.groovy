/*
 * Copyright 2024, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nextflow.lsp.services.script

import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeStringUtils
import nextflow.lsp.ast.ASTUtils
import nextflow.lsp.services.CompletionProvider
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import nextflow.script.dsl.FeatureFlag
import nextflow.script.dsl.FeatureFlagDsl
import nextflow.script.dsl.ScriptDsl
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.OutputNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.ClassExpression
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

    private static final List<CompletionItem> TOPLEVEL_ITEMS = []
    
    static {
        final List<List<String>> snippets = [
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
        ]

        final featureFlags = FeatureFlagDsl.class
        for( final field : featureFlags.getDeclaredFields() ) {
            final annot = field.getAnnotation(FeatureFlag)
            if( !annot )
                continue
            snippets.add([
                annot.name(),
                annot.description(),
                "${annot.name()} = ".toString()
            ])
        }

        snippets.each { name, documentation, insertText ->
            final item = new CompletionItem(name)
            item.setKind(CompletionItemKind.Snippet)
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation.stripIndent(true).trim()))
            item.setInsertText(insertText.stripIndent(true).trim())
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            TOPLEVEL_ITEMS.add(item)
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
        final topNode = nodeTree.last()

        isIncomplete = false
        final List<CompletionItem> items = []

        if( offsetNode instanceof VariableExpression ) {
            // e.g. "foo "
            //          ^
            final namePrefix = offsetNode.getName()
            log.debug "completion variable -- '${namePrefix}'"
            populateItemsFromScope(offsetNode, namePrefix, topNode, items)
        }
        else if( offsetNode instanceof ConstantExpression ) {
            final parentNode = ast.getParent(offsetNode)
            if( parentNode instanceof MethodCallExpression ) {
                final namePrefix = parentNode.getMethodAsString()
                log.debug "completion method call -- '${namePrefix}'"
                if( parentNode.isImplicitThis() ) {
                    // e.g. "foo ()"
                    //          ^
                    populateItemsFromScope(offsetNode, namePrefix, topNode, items)
                }
                else {
                    // e.g. "foo.bar ()"
                    //              ^
                    populateMethodsFromObjectScope(parentNode.getObjectExpression(), namePrefix, items)
                }
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
            populateTypes(namePrefix, items)
        }
        else if( offsetNode instanceof PropertyExpression ) {
            // e.g. "foo.bar. "
            //               ^
            log.debug "completion property -- ''"
            populateItemsFromObjectScope(offsetNode.getObjectExpression(), '', items)
        }
        else {
            log.debug "completion ${offsetNode.class.simpleName} -- '${offsetNode.getText()}'"
            populateItemsFromScope(offsetNode, '', topNode, items)
        }

        return isIncomplete
            ? Either.forRight(new CompletionList(true, items))
            : Either.forLeft(items)
    }

    private void populateItemsFromObjectScope(Expression object, String namePrefix, List<CompletionItem> items) {
        ClassNode cn = ASTUtils.getTypeOfNode(object, ast)
        while( cn != null && !ClassHelper.isObjectType(cn) ) {
            final isStatic = object instanceof ClassExpression
            final Set<String> existingNames = []

            final fields = ASTUtils.getFieldsForType(cn, isStatic, ast)
            populateItemsFromFields(fields, namePrefix, existingNames, items)

            final methods = ASTUtils.getMethodsForType(cn, isStatic, ast)
            populateItemsFromMethods(methods, namePrefix, existingNames, items)

            cn = cn.getSuperClass()
        }
    }

    private void populateMethodsFromObjectScope(Expression object, String namePrefix, List<CompletionItem> items) {
        ClassNode cn = ASTUtils.getTypeOfNode(object, ast)
        while( cn != null && !ClassHelper.isObjectType(cn) ) {
            final isStatic = object instanceof ClassExpression
            final Set<String> existingNames = []

            final methods = ASTUtils.getMethodsForType(cn, isStatic, ast)
            populateItemsFromMethods(methods, namePrefix, existingNames, items)

            cn = cn.getSuperClass()
        }
    }

    private void populateItemsFromFields(Iterator<FieldNode> fields, String namePrefix, Set<String> existingNames, List<CompletionItem> items) {
        for( final field : fields ) {
            final name = field.getName()
            if( !name.startsWith(namePrefix) || existingNames.contains(name) )
                continue
            existingNames.add(name)

            final item = new CompletionItem()
            item.setLabel(field.getName())
            item.setKind(astNodeToCompletionItemKind(field))
            item.setDetail(astNodeToCompletionItemDetail(field))

            final documentation = ASTNodeStringUtils.getDocumentation(field)
            if( documentation != null )
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation))

            if( !addItem(item, items) )
                break
        }
    }

    private void populateItemsFromMethods(Iterator<MethodNode> methods, String namePrefix, Set<String> existingNames, List<CompletionItem> items) {
        for( final method : methods ) {
            final name = method.getName()
            if( !name.startsWith(namePrefix) || existingNames.contains(name) )
                continue
            existingNames.add(name)

            final item = new CompletionItem()
            item.setLabel(method.getName())
            item.setKind(astNodeToCompletionItemKind(method))
            item.setDetail(astNodeToCompletionItemDetail(method))

            final documentation = ASTNodeStringUtils.getDocumentation(method)
            if( documentation != null )
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation))

            if( !addItem(item, items) )
                break
        }
    }

    private void populateItemsFromScope(ASTNode node, String namePrefix, ASTNode topNode, List<CompletionItem> items) {
        final Set<String> existingNames = []
        VariableScope scope = ASTUtils.getVariableScope(node, ast)
        while( scope != null ) {
            populateItemsFromScope0(scope, namePrefix, existingNames, items)
            scope = scope.parent
        }

        if( topNode instanceof FunctionNode || topNode instanceof ProcessNode || topNode instanceof OutputNode )
            populateExternalFunctions(namePrefix, items)

        if( topNode instanceof WorkflowNode ) {
            populateExternalFunctions(namePrefix, items)
            populateExternalProcesses(namePrefix, items)
            populateExternalWorkflows(namePrefix, items)
        }

        populateTypes(namePrefix, items)
    }

    private void populateItemsFromScope0(VariableScope scope, String namePrefix, Set<String> existingNames, List<CompletionItem> items) {
        for( final it = scope.getDeclaredVariablesIterator(); it.hasNext(); ) {
            final variable = it.next()
            final name = variable.getName()
            if( !name.startsWith(namePrefix) )
                continue
            if( existingNames.contains(name) )
                continue
            existingNames.add(name)

            final item = new CompletionItem()
            item.setLabel(name)
            item.setKind(astNodeToCompletionItemKind((ASTNode) variable))
            if( !addItem(item, items) )
                break
        }

        ClassNode cn = scope.getClassScope()
        while( cn != null && !ClassHelper.isObjectType(cn) ) {
            populateItemsFromFields(cn.getFields().iterator(), namePrefix, existingNames, items)
            populateItemsFromMethods(cn.getMethods().iterator(), namePrefix, existingNames, items)
            cn = cn.getSuperClass()
        }
    }

    private void populateExternalFunctions(String namePrefix, List<CompletionItem> items) {
        final localNodes = ast.getFunctionNodes(uri)
        final allNodes = ast.getFunctionNodes()
        populateExternalMethods(namePrefix, localNodes, allNodes, items)
    }

    private void populateExternalProcesses(String namePrefix, List<CompletionItem> items) {
        final localNodes = ast.getProcessNodes(uri)
        final allNodes = ast.getProcessNodes()
        populateExternalMethods(namePrefix, localNodes, allNodes, items)
    }

    private void populateExternalWorkflows(String namePrefix, List<CompletionItem> items) {
        final localNodes = ast.getWorkflowNodes(uri)
        final allNodes = ast.getWorkflowNodes()
        populateExternalMethods(namePrefix, localNodes, allNodes, items)
    }

    private void populateExternalMethods(String namePrefix, List<? extends MethodNode> localNodes, List<? extends MethodNode> allNodes, List<CompletionItem> items) {
        final addIncludeRange = getAddIncludeRange(uri)

        for( final node : allNodes ) {
            final name = node.getName()
            if( !name || !name.startsWith(namePrefix) )
                continue
            if( node in localNodes )
                continue

            final item = new CompletionItem(name)
            item.setKind(astNodeToCompletionItemKind(node))
            item.setDetail(astNodeToCompletionItemDetail(node))

            final documentation = ASTNodeStringUtils.getDocumentation(node)
            if( documentation != null )
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation))

            if( !isIncluded(uri, node) ) {
                final textEdit = createAddIncludeTextEdit(addIncludeRange, uri, name, node)
                item.setAdditionalTextEdits( List.of(textEdit) )
            }

            if( !addItem(item, items) )
                break
        }
    }

    private boolean isIncluded(URI uri, MethodNode node) {
        for( final includeNode : ast.getIncludeNodes(uri) ) {
            for( final module : includeNode.modules ) {
                if( module.getMethod() == node )
                    return true
            }
        }
        return false
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
        final source = Path.of(uri).getParent().relativize(Path.of(ast.getURI(node))).toString()
        final newText = new StringBuilder()
            .append("include { ")
            .append(name)
            .append(" } from '")
            .append(!source.startsWith('.') ? './' : '')
            .append(source)
            .append("'\n")
            .toString()
        return new TextEdit(range, newText)
    }

    private void populateTypes(String namePrefix, List<CompletionItem> items) {
        // add user-defined types
        populateTypes0(ast.getEnumNodes(uri), namePrefix, items)

        // add built-in types
        populateTypes0(ScriptDsl.TYPES, namePrefix, items)
    }

    private void populateTypes0(Collection<ClassNode> classNodes, String namePrefix, List<CompletionItem> items) {
        for( final cn : classNodes ) {
            final item = new CompletionItem()
            item.setLabel(cn.getNameWithoutPackage())
            item.setKind(astNodeToCompletionItemKind(cn))

            final documentation = ASTNodeStringUtils.getDocumentation(cn)
            if( documentation != null )
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, documentation))

            if( !addItem(item, items) )
                break
        }
    }

    private static String astNodeToCompletionItemDetail(ASTNode node) {
        if( node instanceof FunctionNode )
            return 'function'
        if( node instanceof ProcessNode )
            return 'process'
        if( node instanceof WorkflowNode )
            return 'workflow'
        if( Logger.isDebugEnabled() && node instanceof AnnotatedNode )
            return node.getDeclaringClass().getNameWithoutPackage()
        return null
    }

    private static CompletionItemKind astNodeToCompletionItemKind(ASTNode node) {
        if( node instanceof ClassNode ) {
            return node.isEnum()
                ? CompletionItemKind.Enum
                : CompletionItemKind.Class
        }
        if( node instanceof MethodNode ) {
            return CompletionItemKind.Method
        }
        if( node instanceof Variable ) {
            return node instanceof FieldNode
                ? CompletionItemKind.Field
                : CompletionItemKind.Variable
        }
        return CompletionItemKind.Property
    }

    private boolean addItem(CompletionItem item, List<CompletionItem> items) {
        if( items.size() >= maxItemCount ) {
            isIncomplete = true
            return false
        }
        items.add(item)
        return true
    }

}
