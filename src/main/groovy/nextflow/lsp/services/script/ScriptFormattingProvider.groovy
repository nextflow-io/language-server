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

import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.ast.ASTUtils
import nextflow.lsp.services.CustomFormattingOptions
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
import nextflow.script.v2.ScriptVisitor
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.NotExpression
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
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
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
    List<? extends TextEdit> formatting(URI uri, CustomFormattingOptions options) {
        if( ast == null ) {
            log.error("ast cache is empty while providing formatting")
            return Collections.emptyList()
        }

        final sourceUnit = ast.getSourceUnit(uri)
        if( !sourceUnit.getAST() || ast.hasErrors(uri) )
            return Collections.emptyList()

        final oldText = sourceUnit.getSource().getReader().getText()
        final range = new Range(new Position(0, 0), Positions.getPosition(oldText, oldText.size()))
        final visitor = new FormattingVisitor(sourceUnit, options, ast)
        visitor.visit()
        final newText = visitor.toString()

        return List.of( new TextEdit(range, newText) )
    }

}

@CompileStatic
class FormattingVisitor extends ClassCodeVisitorSupport implements ScriptVisitor {

    private SourceUnit sourceUnit

    private CustomFormattingOptions options

    private ASTNodeCache ast

    private StringBuilder builder = new StringBuilder()

    private int indentCount = 0

    private int maxIncludeWidth = 0

    FormattingVisitor(SourceUnit sourceUnit, CustomFormattingOptions options, ASTNodeCache ast) {
        this.sourceUnit = sourceUnit
        this.options = options
        this.ast = ast
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
        if( options.harshilAlignment() )
            maxIncludeWidth = getMaxIncludeWidth(scriptNode.getIncludes())
        for( final featureFlag : scriptNode.getFeatureFlags() )
            visitFeatureFlag(featureFlag)
        for( final includeNode : scriptNode.getIncludes() )
            visitInclude(includeNode)
        if( scriptNode.getEntry() )
            visitWorkflow(scriptNode.getEntry())
        if( scriptNode.getOutput() )
            visitOutput(scriptNode.getOutput())
        for( final workflowNode : scriptNode.getWorkflows() ) {
            if( !workflowNode.isEntry() )
                visitWorkflow(workflowNode)
        }
        for( final processNode : scriptNode.getProcesses() )
            visitProcess(processNode)
        for( final functionNode : scriptNode.getFunctions() )
            visitFunction(functionNode)
    }

    protected int getMaxIncludeWidth(List<IncludeNode> includes) {
        int maxWidth = 0
        for( final includeNode : includes ) {
            for( final module : includeNode.modules ) {
                final width = getIncludeWidth(module)
                if( maxWidth < width )
                    maxWidth = width
            }
        }
        return maxWidth
    }

    protected int getIncludeWidth(IncludeVariable module) {
        return module.alias
            ? module.@name.size() + 4 + module.alias.size()
            : module.@name.size()
    }

    protected void append(String value) {
        builder.append(value)
    }

    protected void appendIndent() {
        final indent = options.insertSpaces()
            ? ' ' * options.tabSize()
            : '\t'
        builder.append(indent * indentCount)
    }

    protected void appendNewLine() {
        append('\n')
    }

    protected void incIndent() {
        indentCount++
    }

    protected void decIndent() {
        indentCount--
    }

    protected void appendComments(ASTNode node) {
        final comments = (List<String>) node.getNodeMetaData(PREPEND_COMMENTS)
        if( !comments )
            return

        for( final line : comments.asReversed() ) {
            if( line == '\n' ) {
                append(line)
            }
            else {
                appendIndent()
                append(line.stripLeading())
            }
        }
    }

    String toString() {
        return builder.toString()
    }

    // script statements

    @Override
    void visitFeatureFlag(FeatureFlagNode node) {
        appendComments(node)
        append(node.name)
        append(' = ')
        visit(node.value)
        appendNewLine()
    }

    @Override
    void visitInclude(IncludeNode node) {
        appendComments(node)
        for( final module : node.modules ) {
            append('include { ')
            append(module.@name)
            if( module.alias ) {
                append(' as ')
                append(module.alias)
            }
            if( options.harshilAlignment() ) {
                final padding = maxIncludeWidth - getIncludeWidth(module)
                append(' ' * padding)
            }
            append(' } from ')
            visit(node.source)
            appendNewLine()
        }
    }

    @Override
    void visitFunction(FunctionNode node) {
        appendComments(node)
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
        appendComments(node)
        append('process ')
        append(node.name)
        append(' {\n')
        incIndent()
        if( node.directives instanceof BlockStatement ) {
            visitDirectives((BlockStatement) node.directives)
            appendNewLine()
        }
        if( node.inputs instanceof BlockStatement ) {
            appendIndent()
            append('input:\n')
            visitDirectives((BlockStatement) node.inputs)
            appendNewLine()
        }
        if( node.outputs instanceof BlockStatement ) {
            appendIndent()
            append('output:\n')
            visitDirectives((BlockStatement) node.outputs)
            appendNewLine()
        }
        if( node.when !instanceof EmptyExpression ) {
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
            appendNewLine()
            appendIndent()
            append('stub:\n')
            visit(node.stub)
        }
        decIndent()
        append('}\n')
    }

    protected void visitDirectives(BlockStatement code) {
        for( final statement : code.statements ) {
            final stmtX = (ExpressionStatement)statement
            final methodCall = (MethodCallExpression)stmtX.expression
            visitDirective(methodCall)
        }
    }

    protected void visitDirective(MethodCallExpression methodCall) {
        appendIndent()
        visitMethodCallExpression(methodCall, true)
        appendNewLine()
    }

    @Override
    void visitWorkflow(WorkflowNode node) {
        appendComments(node)
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
            appendNewLine()
        }
        if( node.main instanceof BlockStatement ) {
            if( node.takes instanceof BlockStatement || node.emits instanceof BlockStatement || node.publishers instanceof BlockStatement ) {
                appendIndent()
                append('main:\n')
            }
            visit(node.main)
        }
        if( node.emits instanceof BlockStatement ) {
            appendNewLine()
            appendIndent()
            append('emit:\n')
            visitWorkflowEmits((BlockStatement) node.emits)
        }
        if( node.publishers instanceof BlockStatement ) {
            appendNewLine()
            appendIndent()
            append('publish:\n')
            visit(node.publishers)
        }
        decIndent()
        append('}\n')
    }

    protected void visitWorkflowEmits(BlockStatement block) {
        final alignmentWidth = options.harshilAlignment()
            ? getWorkflowEmitWidth(block)
            : 0

        for( final stmt : block.statements ) {
            final stmtX = (ExpressionStatement)stmt
            if( stmtX.expression instanceof BinaryExpression ) {
                final binX = (BinaryExpression)stmtX.expression
                final varX = (VariableExpression)binX.getLeftExpression()
                appendIndent()
                visit(varX)
                if( alignmentWidth > 0 ) {
                    final padding = alignmentWidth - varX.name.length()
                    append(' ' * padding)
                }
                append(' = ')
                visit(binX.getRightExpression())
                appendNewLine()
            }
            else {
                visit(stmt)
            }
        }
    }

    protected int getWorkflowEmitWidth(BlockStatement block) {
        if( block.statements.size() == 1 )
            return 0

        int maxWidth = 0
        for( final stmt : block.statements ) {
            final stmtX = (ExpressionStatement)stmt
            int width = 0
            if( stmtX.expression instanceof VariableExpression ) {
                final varX = (VariableExpression)stmtX.expression
                width = varX.name.length()
            }
            else if( stmtX.expression instanceof BinaryExpression ) {
                final binX = (BinaryExpression)stmtX.expression
                final varX = (VariableExpression)binX.getLeftExpression()
                width = varX.name.length()
            }

            if( maxWidth < width )
                maxWidth = width
        }
        return maxWidth
    }

    @Override
    void visitOutput(OutputNode node) {
        appendComments(node)
        append('output {\n')
        incIndent()
        if( node.body instanceof BlockStatement )
            visitOutputBody((BlockStatement) node.body)
        decIndent()
        append('}\n')
    }

    protected void visitOutputBody(BlockStatement block) {
        for( final stmt : block.statements ) {
            if( stmt !instanceof ExpressionStatement )
                continue
            final stmtX = (ExpressionStatement) stmt
            final call = (MethodCallExpression) stmtX.expression

            // treat as target definition
            final args = (ArgumentListExpression) call.arguments
            if( args.size() == 1 && args.first() instanceof ClosureExpression ) {
                final closure = (ClosureExpression) args.first()
                final target = (BlockStatement) closure.code
                appendNewLine()
                appendIndent()
                visit(call.getMethod())
                append(' {\n')
                incIndent()
                visitTargetBody(target)
                decIndent()
                appendIndent()
                append('}\n')
                continue
            }

            // treat as regular directive
            visitDirective(call)
        }
    }

    protected void visitTargetBody(BlockStatement block) {
        for( final stmt : block.statements ) {
            if( stmt !instanceof ExpressionStatement )
                continue
            final stmtX = (ExpressionStatement) stmt
            if( stmtX.expression !instanceof MethodCallExpression )
                continue
            final call = (MethodCallExpression) stmtX.expression

            // treat as index definition
            final name = call.getMethodAsString()
            final args = (ArgumentListExpression) call.arguments
            if( name == 'index' && args.size() == 1 && args.first() instanceof ClosureExpression ) {
                final closure = (ClosureExpression) args.first()
                final index = (BlockStatement) closure.code
                appendNewLine()
                appendIndent()
                append(name)
                append(' {\n')
                incIndent()
                visitDirectives(index)
                decIndent()
                appendIndent()
                append('}\n')
                continue
            }

            // treat as regular directive
            visitDirective(call)
        }
    }

    // statements

    @Override
    void visitIfElse(IfStatement node) {
        visitIfElse(node, true)
    }

    protected void visitIfElse(IfStatement node, boolean preIndent) {
        appendComments(node)
        if( preIndent )
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
            visitIfElse((IfStatement) node.elseBlock, false)
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

    private Expression currentStmtExpr

    @Override
    void visitExpressionStatement(ExpressionStatement node) {
        final cse = currentStmtExpr
        currentStmtExpr = node.expression
        appendComments(node)
        appendIndent()
        if( node.statementLabels ) {
            for( final label : node.statementLabels ) {
                append(label)
                append(': ')
            }
        }
        visit(node.expression)
        appendNewLine()
        currentStmtExpr = cse
    }

    @Override
    void visitReturnStatement(ReturnStatement node) {
        appendComments(node)
        appendIndent()
        append('return ')
        visit(node.expression)
        appendNewLine()
    }

    @Override
    void visitAssertStatement(AssertStatement node) {
        appendComments(node)
        appendIndent()
        append('assert ')
        visit(node.booleanExpression)
        if( !(node.messageExpression instanceof ConstantExpression && node.messageExpression.isNullExpression()) ) {
            append(', ')
            visit(node.messageExpression)
        }
        appendNewLine()
    }

    @Override
    void visitTryCatchFinally(TryCatchStatement node) {
        appendComments(node)
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
        appendComments(node)
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
        visitMethodCallExpression(node, false)
    }

    private boolean inMultilineMethodChain

    protected void visitMethodCallExpression(MethodCallExpression node, boolean directive) {
        final beginMultilineChain = currentStmtExpr == node && getPropertyMethodDepth(node) >= 2
        if( beginMultilineChain )
            inMultilineMethodChain = true
        if( !node.isImplicitThis() ) {
            visit(node.objectExpression)
            if( inMultilineMethodChain ) {
                appendNewLine()
                incIndent()
                appendIndent()
            }
            append('.')
        }
        final immc = inMultilineMethodChain
        inMultilineMethodChain = false
        visit(node.method, false)
        final arguments = (TupleExpression) node.arguments
        if( directive ) {
            append(' ')
            visit(arguments)
            return
        }
        final lastClosureArg = arguments.size() > 0 && arguments.last() instanceof ClosureExpression
        final parenArgs = lastClosureArg
            ? new TupleExpression(arguments.expressions[0..<-1])
            : arguments
        if( parenArgs.size() > 0 || !lastClosureArg ) {
            final newline = isMultilineMethodCall(node)
            append('(')
            if( newline )
                appendNewLine()
            visitArguments(parenArgs, newline)
            if( newline )
                appendIndent()
            append(')')
        }
        if( lastClosureArg ) {
            append(' ')
            visit(arguments.last())
        }
        inMultilineMethodChain = immc
        if( !node.isImplicitThis() && inMultilineMethodChain )
            decIndent()
        if( beginMultilineChain )
            inMultilineMethodChain = false
    }

    protected int getPropertyMethodDepth(Expression node) {
        return node instanceof MethodCallExpression && !node.isImplicitThis()
            ? 1 + getPropertyMethodDepth(node.getObjectExpression())
            : 0
    }

    protected boolean isMultilineMethodCall(MethodCallExpression node) {
        if( currentStmtExpr != node )
            return false
        final defNode = ASTUtils.getMethodFromCallExpression(node, ast)
        return defNode instanceof ProcessNode || defNode instanceof WorkflowNode
    }

    @Override
    void visitConstructorCallExpression(ConstructorCallExpression node) {
        append('new ')
        visitTypeName(node.type)
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
            Expression cse = null
            if( currentStmtExpr == node && node.getOperation().isA(Types.ASSIGNMENT_OPERATOR) ) {
                cse = currentStmtExpr
                currentStmtExpr = node.getRightExpression()
            }
            if( node instanceof DeclarationExpression ) {
                append('def ')
            }
            visit(node.leftExpression)
            append(' ')
            append(node.operation.text)
            append(' ')
            visit(node.rightExpression)
            if( cse )
                currentStmtExpr = cse
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
        final code = (BlockStatement) node.code
        if( code.statements.size() == 0 ) {
            append(' }')
        }
        else if( code.statements.size() == 1 && code.statements.first() instanceof ExpressionStatement ) {
            final stmt = (ExpressionStatement) code.statements.first()
            append(' ')
            visit(stmt.expression)
            append(' }')
        }
        else {
            appendNewLine()
            incIndent()
            visit(code)
            decIndent()
            appendIndent()
            append('}')
        }
    }

    protected void visitParameter(Parameter parameter) {
        final type = parameter.type
        if( !ClassHelper.isObjectType(type) ) {
            visitTypeName(type)
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
        visitArguments(node, false)
    }

    protected void visitArguments(TupleExpression node, boolean newline) {
        final hasNamedArgs = node.getNodeMetaData(NAMED_ARGS)
        final positionalArgs = hasNamedArgs ? node.expressions.tail() : node.expressions
        final comma = newline ? ',' : ', '
        if( newline )
            incIndent()
        for( int i = 0; i < positionalArgs.size(); i++ ) {
            if( newline )
                appendIndent()
            visit(positionalArgs[i])
            if( i + 1 < positionalArgs.size() || hasNamedArgs )
                append(comma)
            if( newline )
                appendNewLine()
        }
        if( hasNamedArgs ) {
            final mapX = (MapExpression)node.expressions.first()
            final namedArgs = mapX.mapEntryExpressions
            for( int i = 0; i < namedArgs.size(); i++ ) {
                if( newline )
                    appendIndent()
                visit(namedArgs[i])
                if( i + 1 < namedArgs.size() )
                    append(comma)
                if( newline )
                    appendNewLine()
            }
        }
        if( newline )
            decIndent()
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
        visitTypeName(node.type)
    }

    @Override
    void visitConstantExpression(ConstantExpression node) {
        visitConstantExpression(node, true)
    }

    protected void visitConstantExpression(ConstantExpression node, boolean quote) {
        if( node.value instanceof String ) {
            final value = (String) node.value
            if( quote ) {
                final quoteChar = (String) node.getNodeMetaData(QUOTE_CHAR, k -> SQ_STR)
                append(quoteChar)
                append(replaceEscapes(value, quoteChar))
                append(quoteChar)
            }
            else {
                append(value)
            }
        }
        else {
            append(node.text)
        }
    }

    private String replaceEscapes(String value, String quoteChar) {
        value = value.replace(quoteChar, '\\' + quoteChar)
        if( quoteChar == SQ_STR || quoteChar == DQ_STR )
            value = value.replace('\n', '\\n')
        return value
    }

    @Override
    void visitClassExpression(ClassExpression node) {
        visitTypeName(node.type)
    }

    protected visitTypeName(ClassNode type) {
        final isFullyQualified = type.getNodeMetaData(FULLY_QUALIFIED)
        append(isFullyQualified ? type.getName() : type.getNameWithoutPackage())
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
        final quoteChar = (String) node.getNodeMetaData(QUOTE_CHAR, k -> DQ_STR)
        final strings = node.strings
        final values = node.values
        append(quoteChar)
        int i = 0
        int j = 0
        while( i < strings.size() || j < values.size() ) {
            final string = i < strings.size() ? strings[i] : null
            final value = j < values.size() ? values[j] : null
            if( isNodeBefore(string, value) ) {
                append(replaceEscapes(string.text, quoteChar))
                i++
            }
            else {
                append('${')
                visit(value)
                append('}')
                j++
            }
        }
        append(quoteChar)
    }

    protected boolean isNodeBefore(ASTNode a, ASTNode b) {
        if( !a )
            return false
        if( !b )
            return true
        if( a.getLineNumber() < b.getLineNumber() )
            return true
        return a.getLineNumber() == b.getLineNumber() && a.getColumnNumber() < b.getColumnNumber()
    }

    @Override
    void visit(Expression node) {
        visit(node, true)
    }

    protected void visit(Expression node, boolean quote) {
        final number = (Number) node.getNodeMetaData(INSIDE_PARENTHESES_LEVEL)
        if( number?.intValue() )
            append('(')
        if( node instanceof ConstantExpression ) {
            visitConstantExpression(node, quote)
        }
        else {
            super.visit(node)
        }
        if( number?.intValue() )
            append(')')
    }

    private static final String SLASH_STR = '/'
    private static final String TDQ_STR = '"""'
    private static final String TSQ_STR = "'''"
    private static final String SQ_STR = "'"
    private static final String DQ_STR = '"'

    private static final String FULLY_QUALIFIED = "_FULLY_QUALIFIED"
    private static final String INSIDE_PARENTHESES_LEVEL = "_INSIDE_PARENTHESES_LEVEL"
    private static final String NAMED_ARGS = "_NAMED_ARGS"
    private static final String PREPEND_COMMENTS = "_PREPEND_COMMENTS"
    private static final String QUOTE_CHAR = "_QUOTE_CHAR"

}
