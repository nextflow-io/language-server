package nextflow.lsp.providers

import nextflow.lsp.compiler.ASTNodeCache
import nextflow.lsp.compiler.ASTUtils
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.eclipse.lsp4j.CompletionContext
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
class CompletionProvider {

    private static final CompletionItem NXF_PROCESS = new CompletionItem('process')

    static {
        NXF_PROCESS.setKind(CompletionItemKind.Method)
        NXF_PROCESS.setInsertText(
            """
            process \${1:MY_PROCESS} {
                input:
                \${2|${NXF_INPUTS*.getLabel().join(',')}|} \${3:var_name}

                output:
                \${4|${NXF_OUTPUTS*.getLabel().join(',')}|} \${5:var_name}

                script:
                \"\"\"
                \${6:MY_SCRIPT}
                \"\"\"
            }
            """.stripIndent(true).trim()
        )
        NXF_PROCESS.setInsertTextFormat(InsertTextFormat.Snippet)
        NXF_PROCESS.setInsertTextMode(InsertTextMode.AdjustIndentation)
    }

    private static final CompletionItem NXF_DIRECTIVES_DEBUG = new CompletionItem('debug')
    private static final CompletionItem[] NXF_DIRECTIVES = new CompletionItem[] {
        NXF_DIRECTIVES_DEBUG,
    }

    static {
        NXF_DIRECTIVES_DEBUG.setInsertText('debug true')
        NXF_DIRECTIVES_DEBUG.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
            'By default the `stdout` produced by the commands executed in all processes is ignored. By setting the `debug` directive to `true`, you can forward the process `stdout` to the current top running process `stdout` file, showing it in the shell terminal.'
        ))
    }

    private static final CompletionItem NXF_INPUTS_VAL = new CompletionItem('val')
    private static final CompletionItem[] NXF_INPUTS = new CompletionItem[] {
        NXF_INPUTS_VAL,
    }

    static {
        NXF_INPUTS_VAL.setInsertText('val ${1:var_name}')
        NXF_INPUTS_VAL.setDocumentation('Declare a variable input. The received value can be any type, and it will be made available to the process body (i.e. `script`, `shell`, `exec`) as a variable with the given name.')
    }

    private static final CompletionItem NXF_OUTPUTS_VAL = new CompletionItem('val')
    private static final CompletionItem[] NXF_OUTPUTS = new CompletionItem[] {
            NXF_OUTPUTS_VAL,
    }

    static {
        NXF_OUTPUTS_VAL.setInsertText('val ${1:var_name}')
        NXF_OUTPUTS_VAL.setDocumentation('Declare a variable output. The argument can be any value, and it can reference any output variables defined in the process body (i.e. variables declared without the `def` keyword).')
    }

    private static Logger log = Logger.instance

    private ASTNodeCache ast
    private int maxItemCount = 100
    private boolean isIncomplete = false

    CompletionProvider(ASTNodeCache ast) {
        this.ast = ast
    }

    /**
     * Get a list of completions for a given completion context. An
     * incomplete list may be returned if the full list exceeds the
     * maximum size.
     *
     * @param textDocument
     * @param position
     * @param context
     */
    Either<List<CompletionItem>, CompletionList> provideCompletion(TextDocumentIdentifier textDocument, Position position, CompletionContext context) {
        if( ast == null ) {
            log.error("ast cache is empty while peoviding completions")
            return Collections.emptyList()
        }

        final uri = URI.create(textDocument.getUri())
        final offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( offsetNode == null )
            return Either.forLeft(Collections.emptyList())

        final parentNode = ast.getParent(offsetNode)

        isIncomplete = false
        final List<CompletionItem> items = []

        if( offsetNode instanceof PropertyExpression )
            populateItemsFromPropertyExpression(offsetNode, position, items)

        else if( parentNode instanceof PropertyExpression )
            populateItemsFromPropertyExpression(parentNode, position, items)

        else if( offsetNode instanceof MethodCallExpression )
            populateItemsFromMethodCallExpression(offsetNode, position, items)

        else if( offsetNode instanceof ConstructorCallExpression )
            populateItemsFromConstructorCallExpression(offsetNode, position, items)

        else if( parentNode instanceof MethodCallExpression )
            populateItemsFromMethodCallExpression(parentNode, position, items)

        else if( offsetNode instanceof VariableExpression )
            populateItemsFromVariableExpression(offsetNode, position, items)

        else if( offsetNode instanceof ClassNode )
            populateItemsFromClassNode(offsetNode, position, items)

        else if( offsetNode instanceof MethodNode )
            populateItemsFromScope(offsetNode, '', items)

        else if( offsetNode instanceof Statement )
            populateItemsFromScope(offsetNode, '', items)

        return isIncomplete
            ? Either.forRight(new CompletionList(true, items))
            : Either.forLeft(items)
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

    private void populateItemsFromClassNode(ClassNode classNode, Position position, List<CompletionItem> items) {
        final parentNode = ast.getParent(classNode)
        if( parentNode !instanceof ClassNode )
            return
        final classRange = LanguageServerUtils.astNodeToRange(classNode)
        final className = getMemberName(classNode.getUnresolvedName(), classRange, position)
        if( classNode == parentNode.getUnresolvedSuperClass() || classNode in parentNode.getUnresolvedInterfaces() )
            populateTypes(classNode, className, new HashSet<>(), items)
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
        final existingNames = new HashSet<>()

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

    private void populateItemsFromProcess(CompletionItem[] values, String memberNamePrefix, Set<String> existingNames, List<CompletionItem> items) {
        for( final item : values ) {
            if( !item.getLabel().startsWith(memberNamePrefix) || existingNames.contains(item.getLabel()) )
                continue
            existingNames.add(item.getLabel())

            item.setKind(CompletionItemKind.Method)
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertTextMode(InsertTextMode.AdjustIndentation)
            items.add(item)
        }
    }

    private void populateItemsFromScope(ASTNode node, String namePrefix, List<CompletionItem> items) {
        boolean inProcess = false
        String processLabel = null

        final existingNames = new HashSet<>()
        ASTNode current = node
        while( current != null ) {
            if( current instanceof ClassNode ) {
                populateItemsFromPropertiesAndFields(current.getProperties(), current.getFields(), namePrefix, existingNames, items)
                populateItemsFromMethods(current.getMethods(), namePrefix, existingNames, items)
            }
            else if( current instanceof MethodNode ) {
                populateItemsFromVariableScope(current.getVariableScope(), namePrefix, existingNames, items)
            }
            else if( current instanceof BlockStatement ) {
                populateItemsFromVariableScope(current.getVariableScope(), namePrefix, existingNames, items)
                if( !inProcess )
                    processLabel = findProcessLabel(node, current)
            }
            else if( current instanceof MethodCallExpression ) {
                inProcess = current.getMethodAsString() == 'process'
            }
            current = ast.getParent(current)
        }

        if( inProcess && processLabel != null && node instanceof VariableExpression ) {
            if( processLabel == 'directives' )
                populateItemsFromProcess(NXF_DIRECTIVES, namePrefix, existingNames, items)
            else if( processLabel == 'input' )
                populateItemsFromProcess(NXF_INPUTS, namePrefix, existingNames, items)
            else if( processLabel == 'output' )
                populateItemsFromProcess(NXF_OUTPUTS, namePrefix, existingNames, items)
        }

        if( !inProcess && 'process'.startsWith(namePrefix) )
            items.add(NXF_PROCESS)

        if( namePrefix.length() == 0 )
            isIncomplete = true
        else
            populateTypes(node, namePrefix, existingNames, items)
    }

    private String findProcessLabel(ASTNode node, BlockStatement block) {
        String result = 'directives'
        for( Statement stmt : block.getStatements() ) {
            if( stmt.getStatementLabel() != null )
                result = stmt.getStatementLabel()
            if( stmt instanceof ExpressionStatement && stmt.getExpression() == node )
                return result
        }
        return null
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
