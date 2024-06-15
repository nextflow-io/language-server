package nextflow.lsp.services.script

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.services.FormattingProvider
import nextflow.lsp.util.Logger
import nextflow.lsp.util.Positions
import nextflow.script.v2.FeatureFlagNode
import nextflow.script.v2.FunctionNode
import nextflow.script.v2.IncludeNode
import nextflow.script.v2.IncludeVariable
import nextflow.script.v2.OutputNode
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.ScriptNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.NotExpression
import org.codehaus.groovy.ast.expr.PostfixExpression
import org.codehaus.groovy.ast.expr.PrefixExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.SpreadMapExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.expr.UnaryPlusExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.AssertStatement
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Types
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit

/**
 * Provide formatting for a script.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptFormattingProvider implements FormattingProvider {

    private static Logger log = Logger.instance

    private ASTNodeCache ast

    ScriptFormattingProvider(ASTNodeCache ast) {
        this.ast = ast
    }

    @Override
    List<? extends TextEdit> formatting(TextDocumentIdentifier textDocument, FormattingOptions options) {
        if( ast == null ) {
            log.error("ast cache is empty while providing formatting")
            return null
        }

        final uri = URI.create(textDocument.getUri())
        final sourceUnit = ast.getSourceUnit(uri)
        final oldText = sourceUnit.getSource().getReader().getText()
        final range = new Range(new Position(0, 0), Positions.getPosition(oldText, oldText.size()))
        final visitor = new FormattingVisitor(sourceUnit, options)
        visitor.visit()
        final newText = visitor.toString()

        return List.of( new TextEdit(range, newText) )
    }

}

@CompileStatic
class FormattingVisitor extends ClassCodeVisitorSupport implements ScriptVisitor {

    private SourceUnit sourceUnit

    private FormattingOptions options

    private StringBuilder builder = new StringBuilder()

    private int indentCount = 0

    private int maxIncludeLength

    FormattingVisitor(SourceUnit sourceUnit, FormattingOptions options) {
        this.sourceUnit = sourceUnit
        this.options = options
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    void visit() {
        final moduleNode = sourceUnit.getAST()
        if( moduleNode !instanceof ScriptNode )
            return
        final scriptNode = (ScriptNode) moduleNode
        for( final featureFlag : scriptNode.getFeatureFlags() )
            visitFeatureFlag(featureFlag)
        maxIncludeLength = getMaxIncludeLength(scriptNode.getIncludes())
        if( scriptNode.getIncludes().size() > 0 )
            append('\n')
        for( final includeNode : scriptNode.getIncludes() )
            visitInclude(includeNode)
        for( final workflowNode : scriptNode.getWorkflows() )
            visitWorkflow(workflowNode)
        for( final functionNode : scriptNode.getFunctions() )
            visitFunction(functionNode)
        for( final processNode : scriptNode.getProcesses() )
            visitProcess(processNode)
        if( scriptNode.getOutput() )
            visitOutput(scriptNode.getOutput())
    }

    protected int getMaxIncludeLength(List<IncludeNode> includes) {
        int maxLength = 0
        for( final includeNode : includes ) {
            for( final module : includeNode.modules ) {
                final length = getIncludeLength(module)
                if( maxLength < length )
                    maxLength = length
            }
        }
        return maxLength
    }

    protected int getIncludeLength(IncludeVariable module) {
        return module.alias
            ? module.@name.size() + 4 + module.alias.size()
            : module.@name.size()
    }

    protected void append(String value) {
        builder.append(value)
    }

    protected void appendIndent() {
        final indent = options.isInsertSpaces()
            ? ' ' * options.getTabSize()
            : '\t'
        builder.append(indent * indentCount)
    }

    protected void incIndent() {
        indentCount++
    }

    protected void decIndent() {
        indentCount--
    }

    String toString() {
        return builder.toString()
    }

    // script statements

    @Override
    void visitFeatureFlag(FeatureFlagNode node) {
        append(node.name)
        append(' = ')
        visit(node.value)
        append('\n')
    }

    @Override
    void visitInclude(IncludeNode node) {
        for( final module : node.modules ) {
            final padding = maxIncludeLength - getIncludeLength(module)
            append('include { ')
            append(module.@name)
            if( module.alias ) {
                append(' as ')
                append(module.alias)
            }
            append(' ' * padding)
            append(' } from \'')
            append(node.source)
            append('\'\n')
        }
    }

    @Override
    void visitFunction(FunctionNode node) {
        append('\n')
        append('def ')
        append(node.name)
        append('(')
        for( int i = 0; i < node.parameters.size(); i++ ) {
            visitParameter(node.parameters[i])
            if( i + 1 < node.parameters.size() )
                append(', ')
        }
        append(') {\n')
        incIndent()
        visit(node.code)
        decIndent()
        append('}\n')
    }

    @Override
    void visitProcess(ProcessNode node) {
        append('\n')
        append('process ')
        append(node.name)
        append(' {\n')
        incIndent()
        if( node.directives !instanceof EmptyStatement ) {
            visit(node.directives)
            append('\n')
        }
        if( node.inputs !instanceof EmptyStatement ) {
            appendIndent()
            append('input:\n')
            visit(node.inputs)
            append('\n')
        }
        if( node.outputs !instanceof EmptyStatement ) {
            appendIndent()
            append('output:\n')
            visit(node.outputs)
            append('\n')
        }
        if( node.when !instanceof EmptyStatement ) {
            appendIndent()
            append('when:\n')
            appendIndent()
            visit(node.when)
            append('\n\n')
        }
        appendIndent()
        append(node.type)
        append(':\n')
        visit(node.exec)
        if( node.stub !instanceof EmptyStatement ) {
            append('\n')
            appendIndent()
            append('stub:\n')
            visit(node.stub)
        }
        decIndent()
        append('}\n')
    }

    @Override
    void visitWorkflow(WorkflowNode node) {
        append('\n')
        append('workflow')
        if( node.name ) {
            append(' ')
            append(node.name)
        }
        append(' {\n')
        incIndent()
        if( node.takes instanceof BlockStatement ) {
            appendIndent()
            append('take:\n')
            visit(node.takes)
            append('\n')
        }
        if( node.main instanceof BlockStatement ) {
            if( node.takes instanceof BlockStatement || node.emits instanceof BlockStatement || node.publishers instanceof BlockStatement ) {
                appendIndent()
                append('main:\n')
            }
            visit(node.main)
        }
        if( node.emits instanceof BlockStatement ) {
            append('\n')
            appendIndent()
            append('emit:\n')
            visit(node.emits)
        }
        if( node.publishers instanceof BlockStatement ) {
            append('\n')
            appendIndent()
            append('publish:\n')
            visit(node.publishers)
        }
        decIndent()
        append('}\n')
    }

    @Override
    void visitOutput(OutputNode node) {
        append('\n')
        append('output {\n')
        incIndent()
        if( node.body instanceof BlockStatement ) {
            visit(node.body)
        }
        decIndent()
        append('}\n')
    }

    // statements

    @Override
    void visitIfElse(IfStatement node) {
        appendIndent()
        append('if (')
        visit(node.booleanExpression)
        append(') {\n')
        incIndent()
        visit(node.ifBlock)
        decIndent()
        appendIndent()
        append('}\n')
        if( node.elseBlock instanceof IfStatement ) {
            appendIndent()
            append('else ')
            visit(node.elseBlock)
        }
        else if( node.elseBlock !instanceof EmptyStatement ) {
            appendIndent()
            append('else {\n')
            incIndent()
            visit(node.elseBlock)
            decIndent()
            appendIndent()
            append('}\n')
        }
    }

    @Override
    void visitExpressionStatement(ExpressionStatement node) {
        appendIndent()
        if( node.statementLabels ) {
            for( final label : node.statementLabels ) {
                append(label)
                append(': ')
            }
        }
        visit(node.expression)
        append('\n')
    }

    @Override
    void visitReturnStatement(ReturnStatement node) {
        appendIndent()
        append('return ')
        visit(node.expression)
        append('\n')
    }

    @Override
    void visitAssertStatement(AssertStatement node) {
        appendIndent()
        append('assert ')
        visit(node.booleanExpression)
        if( !(node.messageExpression instanceof ConstantExpression && node.messageExpression.isNullExpression()) ) {
            append(', ')
            visit(node.messageExpression)
        }
        append('\n')
    }

    @Override
    void visitTryCatchFinally(TryCatchStatement node) {
        appendIndent()
        append('try {\n')
        incIndent()
        visit(node.tryStatement)
        decIndent()
        appendIndent()
        append('}\n')
        for( final catchStatement : node.catchStatements ) {
            visit(catchStatement)
        }
    }

    @Override
    void visitCatchStatement(CatchStatement node) {
        appendIndent()
        append('catch (')
        visitParameter(node.variable)
        append(') {\n')
        incIndent()
        visit(node.code)
        decIndent()
        appendIndent()
        append('}\n')
    }

    // expressions

    @Override
    void visitMethodCallExpression(MethodCallExpression node) {
        if( !node.isImplicitThis() ) {
            visit(node.objectExpression)
            append('.')
        }
        visit(node.method, false)
        final arguments = node.arguments as TupleExpression
        if( arguments.size() == 1 && arguments.first() instanceof ClosureExpression ) {
            append(' ')
            visit(arguments)
        }
        else {
            append('(')
            visit(arguments)
            append(')')
        }
    }

    @Override
    void visitConstructorCallExpression(ConstructorCallExpression node) {
        append('new ')
        append(node.type.name)
        append('(')
        visit(node.arguments)
        append(')')
    }

    @Override
    void visitBinaryExpression(BinaryExpression node) {
        if( node.operation.type == Types.LEFT_SQUARE_BRACKET ) {
            visit(node.leftExpression)
            append('[')
            visit(node.rightExpression)
            append(']')
        }
        else {
            if( node instanceof DeclarationExpression ) {
                append('def ')
            }
            visit(node.leftExpression)
            append(' ')
            append(node.operation.text)
            append(' ')
            visit(node.rightExpression)
        }
    }

    @Override
    void visitTernaryExpression(TernaryExpression node) {
        visit(node.booleanExpression)
        append(' ? ')
        visit(node.trueExpression)
        append(' : ')
        visit(node.falseExpression)
    }

    @Override
    void visitShortTernaryExpression(ElvisOperatorExpression node) {
        visit(node.trueExpression)
        append(' ?: ')
        visit(node.falseExpression)
    }

    @Override
    void visitPostfixExpression(PostfixExpression node) {
        visit(node.expression)
        append(node.operation.text)
    }

    @Override
    void visitPrefixExpression(PrefixExpression node) {
        append(node.operation.text)
        visit(node.expression)
    }

    @Override
    void visitNotExpression(NotExpression node) {
        append('!')
        visit(node.expression)
    }

    @Override
    void visitClosureExpression(ClosureExpression node) {
        append('{')
        if( node.parameters ) {
            append(' ')
            for( int i = 0; i < node.parameters.size(); i++ ) {
                visitParameter(node.parameters[i])
                if( i + 1 < node.parameters.size() )
                    append(', ')
            }
            append(' ->')
        }
        final code = node.code as BlockStatement
        if( code.statements.size() == 0 ) {
            append(' }')
        }
        else if( code.statements.size() == 1 && code.statements.first() instanceof ExpressionStatement ) {
            final stmt = code.statements.first() as ExpressionStatement
            append(' ')
            visit(stmt.expression)
            append(' }')
        }
        else {
            append('\n')
            incIndent()
            visit(code)
            decIndent()
            appendIndent()
            append('}')
        }
    }

    protected void visitParameter(Parameter parameter) {
        final type = parameter.type
        if( type != ClassHelper.OBJECT_TYPE ) {
            append(type.getNameWithoutPackage())
            append(' ')
        }
        append(parameter.name)
        if( parameter.hasInitialExpression() ) {
            append('=')
            visit(parameter.initialExpression)
        }
    }

    @Override
    void visitTupleExpression(TupleExpression node) {
        for( int i = 0; i < node.expressions.size(); i++ ) {
            visit(node.expressions[i])
            if( i + 1 < node.expressions.size() )
                append(', ')
        }
    }

    @Override
    void visitListExpression(ListExpression node) {
        append('[')
        for( int i = 0; i < node.expressions.size(); i++ ) {
            visit(node.expressions[i])
            if( i + 1 < node.expressions.size() )
                append(', ')
        }
        append(']')
    }

    @Override
    void visitMapExpression(MapExpression node) {
        append('[')
        if( !node.mapEntryExpressions )
            append(':')
        for( int i = 0; i < node.mapEntryExpressions.size(); i++ ) {
            visit(node.mapEntryExpressions[i])
            if( i + 1 < node.mapEntryExpressions.size() )
                append(', ')
        }
        append(']')
    }

    @Override
    void visitMapEntryExpression(MapEntryExpression node) {
        visit(node.keyExpression, false)
        append(': ')
        visit(node.valueExpression)
    }

    @Override
    void visitRangeExpression(RangeExpression node) {
        visit(node.from)
        if( node.isExclusiveLeft() )
            append('<')
        append('..')
        if( node.isExclusiveRight() )
            append('<')
        visit(node.to)
    }

    @Override
    void visitSpreadExpression(SpreadExpression node) {
        append('*')
        visit(node.expression)
    }

    @Override
    void visitSpreadMapExpression(SpreadMapExpression node) {
        append('*:')
        visit(node.expression)
    }

    @Override
    void visitUnaryMinusExpression(UnaryMinusExpression node) {
        append('-')
        visit(node.expression)
    }

    @Override
    void visitUnaryPlusExpression(UnaryPlusExpression node) {
        append('+')
        visit(node.expression)
    }

    @Override
    void visitBitwiseNegationExpression(BitwiseNegationExpression node) {
        append('~')
        visit(node.expression)
    }

    @Override
    void visitCastExpression(CastExpression node) {
        visit(node.expression)
        append(' as ')
        append(node.type.name)
    }

    @Override
    void visitConstantExpression(ConstantExpression node) {
        visitConstantExpression(node, true)
    }

    protected void visitConstantExpression(ConstantExpression node, boolean quote) {
        if( node.value instanceof String ) {
            final value = (String) node.value
            if( quote ) append('\'')
            append(replaceEscapes(value, '\''))
            if( quote ) append('\'')
        }
        else {
            append(node.text)
        }
    }

    private String replaceEscapes(String value, String quoteChar) {
        value
            .replace(quoteChar, '\\' + quoteChar)
            .replace('\n', '\\n')
            .replace('\t', '\\t')
    }

    @Override
    void visitClassExpression(ClassExpression node) {
        append(node.text)
    }

    @Override
    void visitVariableExpression(VariableExpression node) {
        append(node.text)
    }

    @Override
    void visitPropertyExpression(PropertyExpression node) {
        visit(node.objectExpression)
        append('.')
        visit(node.property, false)
    }

    @Override
    void visitGStringExpression(GStringExpression node) {
        append('"')
        append(replaceEscapes(node.text, '\"'))
        append('"')
    }

    @Override
    void visit(Expression node) {
        visit(node, true)
    }

    protected void visit(Expression node, boolean quote) {
        final Number number = node.getNodeMetaData(INSIDE_PARENTHESES_LEVEL)
        final k = number != null ? number.intValue() : 0
        append('(' * k)
        if( node instanceof ConstantExpression ) {
            visitConstantExpression(node, quote)
        }
        else {
            super.visit(node)
        }
        append(')' * k)
    }

    private static final String INSIDE_PARENTHESES_LEVEL = "_INSIDE_PARENTHESES_LEVEL"

}
