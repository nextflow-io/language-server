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
package nextflow.lsp.services.config

import groovy.transform.CompileStatic
import nextflow.config.v2.ConfigAppendNode
import nextflow.config.v2.ConfigAssignNode
import nextflow.config.v2.ConfigBlockNode
import nextflow.config.v2.ConfigIncludeNode
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.services.CustomFormattingOptions
import nextflow.lsp.services.FormattingProvider
import nextflow.lsp.util.Logger
import nextflow.lsp.util.Positions
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
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Types
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

/**
 * Provide formatting for a config file.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigFormattingProvider implements FormattingProvider {

    private static Logger log = Logger.instance

    private ASTNodeCache ast

    ConfigFormattingProvider(ASTNodeCache ast) {
        this.ast = ast
    }

    @Override
    List<? extends TextEdit> formatting(URI uri, CustomFormattingOptions options) {
        if( ast == null ) {
            log.error("ast cache is empty while providing formatting")
            return null
        }

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
class FormattingVisitor extends ClassCodeVisitorSupport {

    private SourceUnit sourceUnit

    private CustomFormattingOptions options

    private StringBuilder builder = new StringBuilder()

    private int indentCount = 0

    FormattingVisitor(SourceUnit sourceUnit, CustomFormattingOptions options) {
        this.sourceUnit = sourceUnit
        this.options = options
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit
    }

    void visit() {
        final moduleNode = sourceUnit.getAST()
        if( !moduleNode )
            return
        moduleNode.statementBlock.visit(this)
    }

    protected void append(String value) {
        builder.append(value)
    }

    protected void appendIndent() {
        final indent = options.insertSpaces
            ? ' ' * options.tabSize
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
        if( node instanceof ConfigAssignNode ) {
            visitConfigAssign(node)
        }
        else if( node instanceof ConfigBlockNode ) {
            visitConfigBlock(node)
        }
        else if( node instanceof ConfigIncludeNode ) {
            visitConfigInclude(node)
        }
        else {
            appendIndent()
            visit(node.expression)
            append('\n')
        }
    }

    protected void visitConfigAssign(ConfigAssignNode node) {
        appendIndent()
        append(node.names.join('.'))
        append(node instanceof ConfigAppendNode ? ' ' : ' = ')
        visit(node.value)
        append('\n')
    }

    protected void visitConfigBlock(ConfigBlockNode node) {
        append('\n')
        appendIndent()
        if( node.kind != null ) {
            append(node.kind)
            append(':')
        }
        append(node.name)
        append(' {')
        append('\n')

        incIndent()
        visit(node.block)
        decIndent()

        appendIndent()
        append('}\n')
    }

    protected void visitConfigInclude(ConfigIncludeNode node) {
        appendIndent()
        append('includeConfig ')
        visit(node.source)
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
