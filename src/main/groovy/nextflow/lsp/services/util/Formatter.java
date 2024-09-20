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
package nextflow.lsp.services.util;

import java.util.List;
import java.util.stream.Stream;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.AssertStatement;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.syntax.Types;

import static nextflow.script.v2.ASTHelpers.*;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class Formatter extends CodeVisitorSupport {

    private CustomFormattingOptions options;

    private StringBuilder builder = new StringBuilder();

    private int indentCount = 0;

    public Formatter(CustomFormattingOptions options) {
        this.options = options;
    }

    public void append(char c) {
        builder.append(c);
    }

    public void append(String str) {
        builder.append(str);
    }

    public void appendIndent() {
        var str = options.insertSpaces()
            ? " ".repeat(options.tabSize() * indentCount)
            : "\t".repeat(indentCount);
        builder.append(str);
    }

    public void appendNewLine() {
        builder.append('\n');
    }

    public void appendComments(ASTNode node) {
        var comments = (List<String>) node.getNodeMetaData(PREPEND_COMMENTS);
        if( comments == null || comments.isEmpty() )
            return;

        for( var line : DefaultGroovyMethods.asReversed(comments) ) {
            if( "\n".equals(line) ) {
                append(line);
            }
            else {
                appendIndent();
                append(line.stripLeading());
            }
        }
    }

    public void incIndent() {
        indentCount++;
    }

    public void decIndent() {
        indentCount--;
    }

    public String toString() {
        return builder.toString();
    }

    // statements

    @Override
    public void visitIfElse(IfStatement node) {
        visitIfElse(node, true);
    }

    protected void visitIfElse(IfStatement node, boolean preIndent) {
        appendComments(node);
        if( preIndent )
            appendIndent();
        append("if (");
        visit(node.getBooleanExpression());
        append(") {\n");
        incIndent();
        visit(node.getIfBlock());
        decIndent();
        appendIndent();
        append("}\n");
        if( node.getElseBlock() instanceof IfStatement is ) {
            appendIndent();
            append("else ");
            visitIfElse(is, false);
        }
        else if( !(node.getElseBlock() instanceof EmptyStatement) ) {
            appendIndent();
            append("else {\n");
            incIndent();
            visit(node.getElseBlock());
            decIndent();
            appendIndent();
            append("}\n");
        }
    }

    private Expression currentStmtExpr;

    @Override
    public void visitExpressionStatement(ExpressionStatement node) {
        var cse = currentStmtExpr;
        currentStmtExpr = node.getExpression();
        appendComments(node);
        appendIndent();
        if( node.getStatementLabels() != null ) {
            for( var label : node.getStatementLabels() ) {
                append(label);
                append(": ");
            }
        }
        visit(node.getExpression());
        appendNewLine();
        currentStmtExpr = cse;
    }

    @Override
    public void visitReturnStatement(ReturnStatement node) {
        appendComments(node);
        appendIndent();
        append("return ");
        visit(node.getExpression());
        appendNewLine();
    }

    @Override
    public void visitAssertStatement(AssertStatement node) {
        appendComments(node);
        appendIndent();
        append("assert ");
        visit(node.getBooleanExpression());
        if( !(node.getMessageExpression() instanceof ConstantExpression ce && ce.isNullExpression()) ) {
            append(", ");
            visit(node.getMessageExpression());
        }
        appendNewLine();
    }

    @Override
    public void visitTryCatchFinally(TryCatchStatement node) {
        appendComments(node);
        appendIndent();
        append("try {\n");
        incIndent();
        visit(node.getTryStatement());
        decIndent();
        appendIndent();
        append("}\n");
        for( var catchStatement : node.getCatchStatements() ) {
            visit(catchStatement);
        }
    }

    @Override
    public void visitCatchStatement(CatchStatement node) {
        appendComments(node);
        appendIndent();
        append("catch (");

        var variable = node.getVariable();
        var type = variable.getType();
        if( !ClassHelper.isObjectType(type) ) {
            append(type.getNameWithoutPackage());
            append(' ');
        }
        append(variable.getName());

        append(") {\n");
        incIndent();
        visit(node.getCode());
        decIndent();
        appendIndent();
        append("}\n");
    }

    // expressions

    private boolean inWrappedMethodChain;

    @Override
    public void visitMethodCallExpression(MethodCallExpression node) {
        var beginWrappedMethodChain = shouldWrapMethodChain(node);
        if( beginWrappedMethodChain )
            inWrappedMethodChain = true;

        if( !node.isImplicitThis() ) {
            visit(node.getObjectExpression());
            if( inWrappedMethodChain ) {
                appendNewLine();
                incIndent();
                appendIndent();
            }
            append('.');
        }

        visit(node.getMethod(), false);

        var iwmc = inWrappedMethodChain;
        inWrappedMethodChain = false;
        var args = asMethodCallArguments(node);
        var lastClosureArg = args.size() > 0 && args.get(args.size() - 1) instanceof ClosureExpression;
        var parenArgs = lastClosureArg
            ? DefaultGroovyMethods.init(args)
            : args;
        if( parenArgs.size() > 0 || !lastClosureArg ) {
            var wrap = shouldWrapMethodCall(node);
            append('(');
            if( wrap )
                incIndent();
            visitArguments(parenArgs, wrap);
            if( wrap ) {
                appendNewLine();
                decIndent();
                appendIndent();
            }
            append(')');
        }
        if( lastClosureArg ) {
            append(' ');
            visit(args.get(args.size() - 1));
        }
        inWrappedMethodChain = iwmc;

        if( !node.isImplicitThis() && inWrappedMethodChain )
            decIndent();
        if( beginWrappedMethodChain )
            inWrappedMethodChain = false;
    }

    @Override
    public void visitConstructorCallExpression(ConstructorCallExpression node) {
        append("new ");
        visitTypeName(node.getType());
        append('(');
        visitArguments(asMethodCallArguments(node), false);
        append(')');
    }

    public void visitArguments(List<Expression> args, boolean wrap) {
        var hasNamedArgs = args.size() > 0 && args.get(0) instanceof NamedArgumentListExpression;
        var positionalArgs = hasNamedArgs
            ? DefaultGroovyMethods.tail(args)
            : args;
        visitPositionalArgs(positionalArgs, wrap);
        if( hasNamedArgs ) {
            if( positionalArgs.size() > 0 )
                append(wrap ? "," : ", ");
            var mapX = (MapExpression)args.get(0);
            visitNamedArgs(mapX.getMapEntryExpressions(), wrap);
        }
    }

    private boolean inWrappedPipeChain;

    @Override
    public void visitBinaryExpression(BinaryExpression node) {
        if( node.getOperation().isA(Types.LEFT_SQUARE_BRACKET) ) {
            visit(node.getLeftExpression());
            append('[');
            visit(node.getRightExpression());
            append(']');
            return;
        }

        var beginWrappedPipeChain = shouldWrapPipeExpression(node);
        if( beginWrappedPipeChain )
            inWrappedPipeChain = true;

        Expression cse = null;
        if( currentStmtExpr == node && node.getOperation().isA(Types.ASSIGNMENT_OPERATOR) ) {
            cse = currentStmtExpr;
            currentStmtExpr = node.getRightExpression();
        }

        if( node instanceof DeclarationExpression )
            append("def ");
        visit(node.getLeftExpression());

        if( inWrappedPipeChain ) {
            appendNewLine();
            incIndent();
            appendIndent();
        }
        else {
            append(' ');
        }
        append(node.getOperation().getText());
        append(' ');

        var iwpc = inWrappedPipeChain;
        inWrappedPipeChain = false;
        visit(node.getRightExpression());
        inWrappedPipeChain = iwpc;

        if( inWrappedPipeChain )
            decIndent();

        if( cse != null )
            currentStmtExpr = cse;

        if( beginWrappedPipeChain )
            inWrappedPipeChain = false;
    }

    @Override
    public void visitTernaryExpression(TernaryExpression node) {
        if( shouldWrapExpression(node) ) {
            visit(node.getBooleanExpression());
            incIndent();
            appendNewLine();
            appendIndent();
            append("? ");
            visit(node.getTrueExpression());
            appendNewLine();
            appendIndent();
            append(": ");
            visit(node.getFalseExpression());
            decIndent();
        }
        else {
            visit(node.getBooleanExpression());
            append(" ? ");
            visit(node.getTrueExpression());
            append(" : ");
            visit(node.getFalseExpression());
        }
    }

    @Override
    public void visitShortTernaryExpression(ElvisOperatorExpression node) {
        visit(node.getTrueExpression());
        append(" ?: ");
        visit(node.getFalseExpression());
    }

    @Override
    public void visitNotExpression(NotExpression node) {
        append('!');
        visit(node.getExpression());
    }

    @Override
    public void visitClosureExpression(ClosureExpression node) {
        append('{');
        if( node.getParameters() != null ) {
            append(' ');
            visitParameters(node.getParameters());
            append(" ->");
        }
        var code = (BlockStatement) node.getCode();
        if( code.getStatements().size() == 0 ) {
            append(" }");
        }
        else if( code.getStatements().size() == 1 && code.getStatements().get(0) instanceof ExpressionStatement es && !shouldWrapExpression(node) ) {
            append(' ');
            visit(es.getExpression());
            append(" }");
        }
        else {
            appendNewLine();
            incIndent();
            visit(code);
            decIndent();
            appendIndent();
            append('}');
        }
    }

    public void visitParameters(Parameter[] parameters) {
        for( int i = 0; i < parameters.length; i++ ) {
            var param = parameters[i];
            append(param.getName());
            if( param.hasInitialExpression() ) {
                append('=');
                visit(param.getInitialExpression());
            }
            if( i + 1 < parameters.length )
                append(", ");
        }
    }

    @Override
    public void visitTupleExpression(TupleExpression node) {
        var wrap = shouldWrapExpression(node);
        append('(');
        if( wrap )
            incIndent();
        visitPositionalArgs(node.getExpressions(), wrap);
        if( wrap ) {
            appendNewLine();
            decIndent();
            appendIndent();
        }
        append(')');
    }

    @Override
    public void visitListExpression(ListExpression node) {
        var wrap = shouldWrapExpression(node);
        append('[');
        if( wrap )
            incIndent();
        visitPositionalArgs(node.getExpressions(), wrap);
        if( wrap ) {
            appendNewLine();
            decIndent();
            appendIndent();
        }
        append(']');
    }

    protected void visitPositionalArgs(List<Expression> args, boolean wrap) {
        var comma = wrap ? "," : ", ";
        for( int i = 0; i < args.size(); i++ ) {
            if( wrap ) {
                appendNewLine();
                appendIndent();
            }
            visit(args.get(i));
            if( i + 1 < args.size() )
                append(comma);
        }
    }

    @Override
    public void visitMapExpression(MapExpression node) {
        if( node.getMapEntryExpressions().isEmpty() ) {
            append("[:]");
            return;
        }
        var wrap = shouldWrapExpression(node);
        append('[');
        if( wrap )
            incIndent();
        visitNamedArgs(node.getMapEntryExpressions(), wrap);
        if( wrap ) {
            appendNewLine();
            decIndent();
            appendIndent();
        }
        append(']');
    }

    protected void visitNamedArgs(List<MapEntryExpression> args, boolean wrap) {
        var comma = wrap ? "," : ", ";
        for( int i = 0; i < args.size(); i++ ) {
            if( wrap ) {
                appendNewLine();
                appendIndent();
            }
            visit(args.get(i));
            if( i + 1 < args.size() )
                append(comma);
        }
    }

    @Override
    public void visitMapEntryExpression(MapEntryExpression node) {
        visit(node.getKeyExpression(), false);
        append(": ");
        visit(node.getValueExpression());
    }

    @Override
    public void visitRangeExpression(RangeExpression node) {
        visit(node.getFrom());
        if( node.isExclusiveLeft() )
            append('<');
        append("..");
        if( node.isExclusiveRight() )
            append('<');
        visit(node.getTo());
    }

    @Override
    public void visitUnaryMinusExpression(UnaryMinusExpression node) {
        append('-');
        visit(node.getExpression());
    }

    @Override
    public void visitUnaryPlusExpression(UnaryPlusExpression node) {
        append('+');
        visit(node.getExpression());
    }

    @Override
    public void visitBitwiseNegationExpression(BitwiseNegationExpression node) {
        append('~');
        visit(node.getExpression());
    }

    @Override
    public void visitCastExpression(CastExpression node) {
        visit(node.getExpression());
        append(" as ");
        visitTypeName(node.getType());
    }

    @Override
    public void visitConstantExpression(ConstantExpression node) {
        visitConstantExpression(node, true);
    }

    protected void visitConstantExpression(ConstantExpression node, boolean quote) {
        if( node.getValue() instanceof String str ) {
            if( quote ) {
                var quoteChar = (String) node.getNodeMetaData(QUOTE_CHAR, k -> SQ_STR);
                append(quoteChar);
                append(replaceEscapes(str, quoteChar));
                append(quoteChar);
            }
            else {
                append(str);
            }
        }
        else {
            append(node.getText());
        }
    }

    @Override
    public void visitClassExpression(ClassExpression node) {
        visitTypeName(node.getType());
    }

    protected void visitTypeName(ClassNode type) {
        var isFullyQualified = type.getNodeMetaData(FULLY_QUALIFIED) != null;
        append(isFullyQualified ? type.getName() : type.getNameWithoutPackage());

        var genericsTypes = type.getGenericsTypes();
        if( genericsTypes != null ) {
            append('<');
            for( int i = 0; i < genericsTypes.length; i++ ) {
                if( i > 0 )
                    append(", ");
                visitTypeName(genericsTypes[i].getType());
            }
            append('>');
        }
    }

    @Override
    public void visitVariableExpression(VariableExpression node) {
        append(node.getText());
    }

    @Override
    public void visitPropertyExpression(PropertyExpression node) {
        visit(node.getObjectExpression());
        append('.');
        visit(node.getProperty(), false);
    }

    @Override
    public void visitGStringExpression(GStringExpression node) {
        var quoteChar = (String) node.getNodeMetaData(QUOTE_CHAR, k -> DQ_STR);
        append(quoteChar);
        var stream = Stream.concat(
            node.getStrings().stream().map(v -> (Expression) v),
            node.getValues().stream()
        );
        stream
            .sorted((a, b) ->
                a.getLineNumber() != b.getLineNumber()
                    ? a.getLineNumber() - b.getLineNumber()
                    : a.getColumnNumber() - b.getColumnNumber()
            )
            .forEach((part) -> {
                if( part instanceof ConstantExpression ce && ce.getValue() instanceof String str ) {
                    append(replaceEscapes(str, quoteChar));
                }
                else {
                    append("${");
                    visit(part);
                    append('}');
                }
            });
        append(quoteChar);
    }

    @Override
    public void visit(Expression node) {
        visit(node, true);
    }

    protected void visit(Expression node, boolean quote) {
        var number = (Number) node.getNodeMetaData(INSIDE_PARENTHESES_LEVEL);
        if( number != null && number.intValue() > 0 )
            append('(');
        if( node instanceof ConstantExpression ce ) {
            visitConstantExpression(ce, quote);
        }
        else {
            super.visit(node);
        }
        if( number != null && number.intValue() > 0 )
            append(')');
    }

    // helpers

    private String replaceEscapes(String value, String quoteChar) {
        if( quoteChar == SQ_STR || quoteChar == DQ_STR ) {
            value = value.replace("\n", "\\n");
            value = value.replace("\t", "\\t");
        }
        return value;
    }

    private boolean shouldWrapExpression(Expression node) {
        return node.getLineNumber() < node.getLastLineNumber();
    }

    private boolean shouldWrapMethodCall(MethodCallExpression node) {
        var start = node.getMethod();
        var end = node.getArguments();
        return start.getLineNumber() < end.getLastLineNumber();
    }

    private boolean shouldWrapMethodChain(MethodCallExpression node) {
        if( currentStmtExpr != node )
            return false;
        if( !shouldWrapExpression(node) )
            return false;

        Expression root = node;
        int depth = 0;
        while( root instanceof MethodCallExpression mce && !mce.isImplicitThis() ) {
            root = mce.getObjectExpression();
            depth += 1;
        }

        return shouldWrapExpression(root)
            ? false
            : depth >= 2;
    }

    private boolean shouldWrapPipeExpression(BinaryExpression node) {
        return currentStmtExpr == node && node.getOperation().isA(Types.PIPE) && shouldWrapExpression(node);
    }

    private static final String SLASH_STR = "/";
    private static final String TDQ_STR = "\"\"\"";
    private static final String TSQ_STR = "'''";
    private static final String SQ_STR = "'";
    private static final String DQ_STR = "\"";

    private static final String FULLY_QUALIFIED = "_FULLY_QUALIFIED";
    private static final String INSIDE_PARENTHESES_LEVEL = "_INSIDE_PARENTHESES_LEVEL";
    private static final String PREPEND_COMMENTS = "_PREPEND_COMMENTS";
    private static final String QUOTE_CHAR = "_QUOTE_CHAR";

}
