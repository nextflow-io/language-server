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
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.syntax.Types;

import static nextflow.script.v2.ASTHelpers.*;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class Formatter extends CodeVisitorSupport {

    public static String prettyPrintTypeName(ClassNode type) {
        var fmt = new Formatter(new FormattingOptions(0, false, false));
        fmt.visitTypeName(type);
        return fmt.toString();
    }

    private FormattingOptions options;

    private StringBuilder builder = new StringBuilder();

    private int indentCount = 0;

    public Formatter(FormattingOptions options) {
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

    public void appendLeadingComments(ASTNode node) {
        var comments = (List<String>) node.getNodeMetaData(LEADING_COMMENTS);
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

    public void appendTrailingComment(ASTNode node) {
        var comment = (String) node.getNodeMetaData(TRAILING_COMMENT);
        if( comment != null ) {
            append(' ');
            append(comment);
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
        appendLeadingComments(node);
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
        appendLeadingComments(node);
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
        appendLeadingComments(node);
        appendIndent();
        append("return ");
        visit(node.getExpression());
        appendNewLine();
    }

    @Override
    public void visitAssertStatement(AssertStatement node) {
        appendLeadingComments(node);
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
        appendLeadingComments(node);
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
    public void visitThrowStatement(ThrowStatement node) {
        appendLeadingComments(node);
        appendIndent();
        append("throw ");
        visit(node.getExpression());
        appendNewLine();
    }

    @Override
    public void visitCatchStatement(CatchStatement node) {
        appendLeadingComments(node);
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

        visit(node.getMethod());

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
        visit(node.getKeyExpression());
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
        var text = (String) node.getNodeMetaData(VERBATIM_TEXT);
        if( text != null )
            append(text);
        else
            append(node.getText());
    }

    @Override
    public void visitClassExpression(ClassExpression node) {
        visitTypeName(node.getType());
    }

    protected void visitTypeName(ClassNode type) {
        if( type.isArray() ) {
            visitTypeName(type.getComponentType());
            return;
        }

        var fullyQualified = type.getNodeMetaData(FULLY_QUALIFIED) != null;
        var placeholder = type.isGenericsPlaceHolder();
        var name = fullyQualified
            ? type.getName()
            : placeholder ? type.getUnresolvedName() : type.getNameWithoutPackage();
        append(nextflow.script.dsl.Types.normalize(name));

        var genericsTypes = type.getGenericsTypes();
        if( !placeholder && genericsTypes != null ) {
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
        visit(node.getProperty());
    }

    @Override
    public void visitGStringExpression(GStringExpression node) {
        var quoteChar = (String) node.getNodeMetaData(QUOTE_CHAR, k -> DQ_STR);
        append(quoteChar);
        Stream
            .concat(
                node.getStrings().stream().map(v -> (Expression) v),
                node.getValues().stream()
            )
            .sorted((a, b) ->
                a.getLineNumber() != b.getLineNumber()
                    ? a.getLineNumber() - b.getLineNumber()
                    : a.getColumnNumber() - b.getColumnNumber()
            )
            .forEach((part) -> {
                if( part.getNodeMetaData(VERBATIM_TEXT) != null ) {
                    visit(part);
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
        var number = (Number) node.getNodeMetaData(INSIDE_PARENTHESES_LEVEL);
        if( number != null && number.intValue() > 0 )
            append('(');
        super.visit(node);
        if( number != null && number.intValue() > 0 )
            append(')');
    }

    // helpers

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
    private static final String LEADING_COMMENTS = "_LEADING_COMMENTS";
    private static final String QUOTE_CHAR = "_QUOTE_CHAR";
    private static final String TRAILING_COMMENT = "_TRAILING_COMMENT";
    private static final String VERBATIM_TEXT = "_VERBATIM_TEXT";

}
