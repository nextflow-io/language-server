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

import java.util.regex.Pattern

import groovy.transform.CompileStatic
import nextflow.config.v2.ConfigAppendNode
import nextflow.config.v2.ConfigAssignNode
import nextflow.config.v2.ConfigBlockNode
import nextflow.config.v2.ConfigIncludeNode
import nextflow.config.v2.ConfigNode
import nextflow.config.v2.ConfigVisitor
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.services.CustomFormattingOptions
import nextflow.lsp.services.FormattingProvider
import nextflow.lsp.util.Logger
import nextflow.lsp.util.Positions
import org.codehaus.groovy.ast.ASTNode
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
            return Collections.emptyList()
        }

        final sourceUnit = ast.getSourceUnit(uri)
        if( !sourceUnit.getAST() || ast.hasErrors(uri) )
            return Collections.emptyList()

        final oldText = sourceUnit.getSource().getReader().getText()
        final range = new Range(new Position(0, 0), Positions.getPosition(oldText, oldText.size()))
        final visitor = new FormattingVisitor(sourceUnit, options)
        visitor.visit()
        final newText = visitor.toString()

        return List.of( new TextEdit(range, newText) )
    }

}

@CompileStatic
class FormattingVisitor extends ClassCodeVisitorSupport implements ConfigVisitor {

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
        if( moduleNode !instanceof ConfigNode )
            return
        super.visit((ConfigNode) moduleNode)
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

    // config statements

    @Override
    void visitConfigAssign(ConfigAssignNode node) {
        appendComments(node)
        appendIndent()
        final name = node.names.join('.')
        append(name)
        if( currentAlignmentWidth > 0 ) {
            final padding = currentAlignmentWidth - name.length()
            append(' ' * padding)
        }
        append(node instanceof ConfigAppendNode ? ' ' : ' = ')
        visit(node.value)
        appendNewLine()
    }

    private static final Pattern IDENTIFIER = ~/[a-zA-Z_]+[a-zA-Z0-9_]*/

    private int currentAlignmentWidth = 0

    @Override
    void visitConfigBlock(ConfigBlockNode node) {
        appendComments(node)
        appendIndent()
        if( node.kind != null ) {
            append(node.kind)
            append(': ')
        }
        final name = node.name
        if( IDENTIFIER.matcher(name).matches() ) {
            append(name)
        }
        else {
            append("'")
            append(name)
            append("'")
        }
        append(' {')
        appendNewLine()

        int caw
        if( options.harshilAlignment() ) {
            int maxWidth = 0
            for( final stmt : node.statements ) {
                if( stmt !instanceof ConfigAssignNode )
                    continue
                final width = ((ConfigAssignNode) stmt).names.join('.').length()
                if( maxWidth < width )
                    maxWidth = width
            }
            caw = currentAlignmentWidth
            currentAlignmentWidth = maxWidth
        }

        incIndent()
        super.visitConfigBlock(node)
        decIndent()

        if( options.harshilAlignment() )
            currentAlignmentWidth = caw

        appendIndent()
        append('}')
        appendNewLine()
    }

    @Override
    void visitConfigInclude(ConfigIncludeNode node) {
        appendComments(node)
        appendIndent()
        append('includeConfig ')
        visit(node.source)
        appendNewLine()
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

    @Override
    void visitExpressionStatement(ExpressionStatement node) {
        appendComments(node)
        appendIndent()
        visit(node.expression)
        appendNewLine()
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

    // expressions

    @Override
    void visitMethodCallExpression(MethodCallExpression node) {
        if( !node.isImplicitThis() ) {
            visit(node.objectExpression)
            append('.')
        }
        visit(node.method, false)
        final arguments = (TupleExpression) node.arguments
        final lastClosureArg = arguments.size() > 0 && arguments.last() instanceof ClosureExpression
        final parenArgs = lastClosureArg
            ? new TupleExpression(arguments.expressions[0..<-1])
            : arguments
        if( parenArgs.size() > 0 || !lastClosureArg ) {
            append('(')
            visit(parenArgs)
            append(')')
        }
        if( lastClosureArg ) {
            append(' ')
            visit(arguments.last())
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
        final hasNamedArgs = node.getNodeMetaData(NAMED_ARGS)
        final positionalArgs = hasNamedArgs ? node.expressions.tail() : node.expressions
        for( int i = 0; i < positionalArgs.size(); i++ ) {
            visit(positionalArgs[i])
            if( i + 1 < positionalArgs.size() )
                append(', ')
        }
        if( hasNamedArgs ) {
            if( positionalArgs )
                append(', ')
            final mapX = (MapExpression)node.expressions.first()
            final namedArgs = mapX.mapEntryExpressions
            for( int i = 0; i < namedArgs.size(); i++ ) {
                visit(namedArgs[i])
                if( i + 1 < namedArgs.size() )
                    append(', ')
            }
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

    private static final String INSIDE_PARENTHESES_LEVEL = "_INSIDE_PARENTHESES_LEVEL"
    private static final String NAMED_ARGS = "_NAMED_ARGS"
    private static final String PREPEND_COMMENTS = "_PREPEND_COMMENTS"
    private static final String QUOTE_CHAR = "_QUOTE_CHAR"

}
