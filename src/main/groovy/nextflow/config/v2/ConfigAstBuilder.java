/*
 * Copyright 2013-2024, Seqera Labs
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
package nextflow.config.v2;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.google.common.hash.Hashing;
import groovy.lang.Tuple2;
import nextflow.antlr.ConfigLexer;
import nextflow.antlr.ConfigParser;
import nextflow.antlr.DescriptiveErrorStrategy;
import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.groovy.parser.antlr4.GroovySyntaxError;
import org.apache.groovy.parser.antlr4.util.StringUtils;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.NodeMetaDataHandler;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.SpreadExpression;
import org.codehaus.groovy.ast.expr.SpreadMapExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.stmt.AssertStatement;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.runtime.IOGroovyMethods;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.codehaus.groovy.syntax.Numbers;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Types;

import static nextflow.antlr.ConfigParser.*;
import static nextflow.antlr.PositionConfigureUtils.ast;
import static org.codehaus.groovy.ast.tools.GeneralUtils.*;

/**
 * Transform a Nextflow config parse tree into a Groovy AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigAstBuilder {

    private SourceUnit sourceUnit;
    private ConfigNode moduleNode;
    private ConfigLexer lexer;
    private ConfigParser parser;

    private Tuple2<ParserRuleContext,Exception> numberFormatError;

    public ConfigAstBuilder(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
        this.moduleNode = new ConfigNode(sourceUnit);

        var charStream = createCharStream(sourceUnit);
        this.lexer = new ConfigLexer(charStream);
        this.parser = new ConfigParser(new CommonTokenStream(lexer));
        parser.setErrorHandler(new DescriptiveErrorStrategy(charStream));
    }

    private CharStream createCharStream(SourceUnit sourceUnit) {
        try {
            return CharStreams.fromReader(
                    new BufferedReader(sourceUnit.getSource().getReader()),
                    sourceUnit.getName());
        }
        catch( IOException e ) {
            throw new RuntimeException("Error occurred when reading source code.", e);
        }
    }

    private CompilationUnitContext buildCST() {
        try {
            var tokenStream = parser.getInputStream();
            try {
                return buildCST(PredictionMode.SLL);
            }
            catch( Throwable t ) {
                // if some syntax error occurred in the lexer, no need to retry the powerful LL mode
                if( t instanceof GroovySyntaxError gse && gse.getSource() == GroovySyntaxError.LEXER )
                    throw t;

                tokenStream.seek(0);
                return buildCST(PredictionMode.LL);
            }
        }
        catch( Throwable t ) {
            throw convertException(t);
        }
    }

    private CompilationUnitContext buildCST(PredictionMode predictionMode) {
        parser.getInterpreter().setPredictionMode(predictionMode);

        removeErrorListeners();
        if( predictionMode == PredictionMode.LL )
            addErrorListeners();

        return parser.compilationUnit();
    }

    private CompilationFailedException convertException(Throwable t) {
        if( t instanceof CompilationFailedException cfe )
            return cfe;
        else if( t instanceof ParseCancellationException )
            return createParsingFailedException(t.getCause());
        else
            return createParsingFailedException(t);
    }

    public ModuleNode buildAST() {
        try {
            return compilationUnit(buildCST());
        }
        catch( Throwable t ) {
            throw convertException(t);
        }
    }

    /// CONFIG STATEMENTS

    private ModuleNode compilationUnit(CompilationUnitContext ctx) {
        for( var stmt : ctx.configStatement() )
            moduleNode.addConfigStatement(configStatement(stmt));

        var scriptClassNode = moduleNode.getScriptClassDummy();
        scriptClassNode.setName(getMainClassName());

        var statements = moduleNode.getConfigStatements();
        if( scriptClassNode != null && !statements.isEmpty() ) {
            var first = statements.get(0);
            var last = statements.get(statements.size() - 1);
            scriptClassNode.setSourcePosition(first);
            scriptClassNode.setLastColumnNumber(last.getLastColumnNumber());
            scriptClassNode.setLastLineNumber(last.getLastLineNumber());
        }

        if( numberFormatError != null )
            throw createParsingFailedException(numberFormatError.getV2().getMessage(), numberFormatError.getV1());

        return moduleNode;
    }

    private String getMainClassName() {
        try {
            var reader = sourceUnit.getSource().getReader();
            var text = IOGroovyMethods.getText(reader);
            var hash = Hashing.sipHash24().newHasher().putUnencodedChars(text).hash();
            return "_nf_config_" + hash.toString();
        }
        catch( IOException e ) {
            throw new GroovyBugError("Failed to create main class name", e);
        }
    }

    private ConfigStatement configStatement(ConfigStatementContext ctx) {
        if( ctx instanceof ConfigIncludeStmtAltContext ciac )
            return ast( configInclude(ciac.configInclude()), ciac );

        if( ctx instanceof ConfigAssignmentStmtAltContext caac )
            return ast( configAssignment(caac.configAssignment()), caac );

        if( ctx instanceof ConfigBlockStmtAltContext cbac )
            return ast( configBlock(cbac.configBlock()), cbac );

        if( ctx instanceof ConfigBlockAltStmtAltContext cbac )
            return ast( configBlockAlt(cbac.configBlockAlt()), cbac );

        if( ctx instanceof ConfigIncompleteStmtAltContext ciac )
            return ast( configIncomplete(ciac.configIncomplete()), ciac );

        throw createParsingFailedException("Invalid statement: " + ctx.getText(), ctx);
    }

    private ConfigStatement configInclude(ConfigIncludeContext ctx) {
        var source = expression(ctx.expression());
        return new ConfigIncludeNode(source);
    }

    private ConfigStatement configAssignment(ConfigAssignmentContext ctx) {
        var names = ctx.configAssignmentPath().identifier().stream()
            .map(this::identifier)
            .collect(Collectors.toList());
        var value = expression(ctx.expression());
        return ast( new ConfigAssignNode(names, value), ctx );
    }

    private ConfigStatement configBlock(ConfigBlockContext ctx) {
        var name = ctx.identifier() != null
            ? identifier(ctx.identifier())
            : stringLiteral(ctx.stringLiteral());
        var statements = ctx.configBlockStatement().stream()
            .map(this::configBlockStatement)
            .collect(Collectors.toList());
        return new ConfigBlockNode(name, statements);
    }

    private ConfigStatement configBlockStatement(ConfigBlockStatementContext ctx) {
        if( ctx instanceof ConfigIncludeBlockStmtAltContext ciac )
            return ast( configInclude(ciac.configInclude()), ciac );

        if( ctx instanceof ConfigAssignmentBlockStmtAltContext caac )
            return ast( configAssignment(caac.configAssignment()), caac );

        if( ctx instanceof ConfigBlockBlockStmtAltContext cbac )
            return ast( configBlock(cbac.configBlock()), cbac );

        if( ctx instanceof ConfigSelectorBlockStmtAltContext csac )
            return ast( configSelector(csac.configSelector()), csac );

        if( ctx instanceof ConfigIncompleteBlockStmtAltContext ciac )
            return ast( configIncomplete(ciac.configIncomplete()), ciac );

        throw createParsingFailedException("Invalid statement in config block: " + ctx.getText(), ctx);
    }

    private ConfigStatement configSelector(ConfigSelectorContext ctx) {
        var kind = ctx.kind.getText();
        var target = configSelectorTarget(ctx.target);
        var statements = ctx.configAssignment().stream()
            .map(this::configAssignment)
            .collect(Collectors.toList());
        return new ConfigBlockNode(kind, target, statements);
    }

    private String configSelectorTarget(ConfigSelectorTargetContext ctx) {
        return ctx.identifier() != null
            ? identifier(ctx.identifier())
            : stringLiteral(ctx.stringLiteral());
    }

    private ConfigStatement configBlockAlt(ConfigBlockAltContext ctx) {
        var name = identifier(ctx.identifier());
        var statements = ctx.configBlockAltStatement().stream()
            .map(this::configBlockAltStatement)
            .collect(Collectors.toList());
        var result = ast( new ConfigBlockNode(name, statements), ctx );
        if( !"plugins".equals(name) )
            collectSyntaxError(new SyntaxException("Only the `plugins` scope can use the append syntax (i.e. omitting the equals sign)", result));
        return result;
    }

    private ConfigStatement configBlockAltStatement(ConfigBlockAltStatementContext ctx) {
        var name = identifier(ctx.identifier());
        var value = literal(ctx.literal());
        return ast( new ConfigAppendNode(List.of(name), value), ctx );
    }

    private ConfigStatement configIncomplete(ConfigIncompleteContext ctx) {
        var result = ast( new ConfigIncompleteNode(ctx.getText()), ctx );
        collectSyntaxError(new SyntaxException("Incomplete statement", result));
        return result;
    }

    /// GROOVY STATEMENTS

    private Statement statement(StatementContext ctx) {
        if( ctx instanceof IfElseStmtAltContext ieac )
            return ast( ifElseStatement(ieac.ifElseStatement()), ieac );

        if( ctx instanceof ReturnStmtAltContext rac )
            return ast( returnStatement(rac.expression()), rac );

        if( ctx instanceof AssertStmtAltContext aac )
            return ast( assertStatement(aac.assertStatement()), aac );

        if( ctx instanceof VariableDeclarationStmtAltContext vdac )
            return ast( variableDeclaration(vdac.variableDeclaration()), vdac );

        if( ctx instanceof MultipleAssignmentStmtAltContext maac )
            return ast( assignment(maac.multipleAssignmentStatement()), maac );

        if( ctx instanceof AssignmentStmtAltContext aac )
            return ast( assignment(aac.assignmentStatement()), aac );

        if( ctx instanceof ExpressionStmtAltContext eac )
            return ast( expressionStatement(eac.expressionStatement()), eac );

        if( ctx instanceof EmptyStmtAltContext )
            return EmptyStatement.INSTANCE;

        throw createParsingFailedException("Invalid statement: " + ctx.getText(), ctx);
    }

    private Statement ifElseStatement(IfElseStatementContext ctx) {
        var expression = ast( parExpression(ctx.parExpression()), ctx.parExpression() );
        var condition = ast( boolX(expression), expression );
        var thenStmt = statementOrBlock(ctx.tb);
        var elseStmt = ctx.ELSE() != null
            ? statementOrBlock(ctx.fb)
            : EmptyStatement.INSTANCE;
        return ifElseS(condition, thenStmt, elseStmt);
    }

    private Statement statementOrBlock(StatementOrBlockContext ctx) {
        return ctx.statement() != null
            ? statement(ctx.statement())
            : blockStatements(ctx.blockStatements());
    }

    private BlockStatement blockStatements(BlockStatementsContext ctx) {
        if( ctx == null )
            return block(new VariableScope(), Collections.emptyList());
        var statements = ctx.statement().stream()
            .map(this::statement)
            .collect(Collectors.toList());
        return ast( block(new VariableScope(), statements), ctx );
    }

    private Statement returnStatement(ExpressionContext ctx) {
        var result = ctx != null
            ? expression(ctx)
            : ConstantExpression.EMPTY_EXPRESSION;
        return returnS(result);
    }

    private Statement assertStatement(AssertStatementContext ctx) {
        var condition = ast( boolX(expression(ctx.condition)), ctx.condition );
        return ctx.message != null
            ? new AssertStatement(condition, expression(ctx.message))
            : new AssertStatement(condition);
    }

    private Statement variableDeclaration(VariableDeclarationContext ctx) {
        if( ctx.variableNames() != null ) {
            // multiple assignment
            var variables = ctx.variableNames().identifier().stream()
                .map(ident -> {
                    var name = identifier(ident);
                    return (Expression) ast( varX(name), ident );
                })
                .collect(Collectors.toList());
            var target = new ArgumentListExpression(variables);
            var initializer = expression(ctx.initializer);
            return stmt(ast( declX(target, initializer), ctx ));
        }
        else {
            // single assignment
            var name = identifier(ctx.identifier());
            var target = ast( varX(name), ctx.identifier() );
            var initializer = ctx.initializer != null
                ? expression(ctx.initializer)
                : EmptyExpression.INSTANCE;
            return stmt(ast( declX(target, initializer), ctx ));
        }
    }

    private Statement assignment(MultipleAssignmentStatementContext ctx) {
        var left = variableNames(ctx.variableNames());
        var right = expression(ctx.expression());
        return stmt(ast( assignX(left, right), ctx ));
    }

    private Expression variableNames(VariableNamesContext ctx) {
        var vars = ctx.identifier().stream()
            .map(this::variableName)
            .collect(Collectors.toList());
        return ast( new TupleExpression(vars), ctx );
    }

    private Expression variableName(IdentifierContext ctx) {
        return ast( varX(identifier(ctx)), ctx );
    }

    private Statement assignment(AssignmentStatementContext ctx) {
        var left = expression(ctx.left);
        if( left instanceof VariableExpression && isInsideParentheses(left) ) {
            if( left.<Number>getNodeMetaData(INSIDE_PARENTHESES_LEVEL).intValue() > 1 )
                throw createParsingFailedException("Nested parenthesis is not allowed in multiple assignment, e.g. ((a)) = b", ctx);

            var tuple = ast( new TupleExpression(left), ctx.left );
            return stmt(ast( binX(tuple, token(ctx.op), expression(ctx.right)), ctx ));
        }

        if ( isAssignmentLhsValid(left) )
            return stmt(ast( binX(left, token(ctx.op), expression(ctx.right)), ctx ));

        throw createParsingFailedException("The left-hand side of an assignment should be a variable or a property expression", ctx);
    }

    private boolean isAssignmentLhsValid(Expression left) {
        // e.g. p = 123
        if( left instanceof VariableExpression && !isInsideParentheses(left) )
            return true;
        // e.g. obj.p = 123
        if( left instanceof PropertyExpression )
            return true;
        // e.g. map[a] = 123 OR map['a'] = 123 OR map["$a"] = 123
        if( left instanceof BinaryExpression be && be.getOperation().getType() == Types.LEFT_SQUARE_BRACKET )
            return true;
        return false;
    }

    private Statement expressionStatement(ExpressionStatementContext ctx) {
        var base = expression(ctx.expression());
        var expression = ctx.argumentList() != null
            ? methodCall(base, argumentList(ctx.argumentList()))
            : base;
        return ast( stmt(expression), ctx );
    }

    /// GROOVY EXPRESSIONS

    private Expression expression(ExpressionContext ctx) {
        if( ctx instanceof AddSubExprAltContext asac )
            return ast( binary(asac.left, asac.op, asac.right), asac );

        if( ctx instanceof BitwiseAndExprAltContext baac )
            return ast( binary(baac.left, baac.op, baac.right), baac );

        if( ctx instanceof BitwiseOrExprAltContext boac )
            return ast( binary(boac.left, boac.op, boac.right), boac );

        if( ctx instanceof ConditionalExprAltContext cac )
            return ast( ternary(cac), cac );

        if( ctx instanceof EqualityExprAltContext eac )
            return ast( binary(eac.left, eac.op, eac.right), eac );

        if( ctx instanceof ExclusiveOrExprAltContext eoac )
            return ast( binary(eoac.left, eoac.op, eoac.right), eoac );

        if( ctx instanceof LogicalAndExprAltContext laac )
            return ast( binary(laac.left, laac.op, laac.right), laac );

        if( ctx instanceof LogicalOrExprAltContext loac )
            return ast( binary(loac.left, loac.op, loac.right), loac );

        if( ctx instanceof MultDivExprAltContext mdac )
            return ast( binary(mdac.left, mdac.op, mdac.right), mdac );

        if( ctx instanceof PathExprAltContext pac )
            return ast( pathExpression(pac), pac );

        if( ctx instanceof PowerExprAltContext pac )
            return ast( binary(pac.left, pac.op, pac.right), pac );

        if( ctx instanceof RegexExprAltContext rac )
            return ast( binary(rac.left, rac.op, rac.right), rac );

        if( ctx instanceof RelationalExprAltContext rac )
            return ast( binary(rac.left, rac.op, rac.right), rac );

        if( ctx instanceof RelationalCastExprAltContext rcac ) {
            var operand = expression(rcac.expression());
            var type = type(rcac.type());
            return ast( asX(type, operand), rcac );
        }

        if( ctx instanceof RelationalTypeExprAltContext rtac ) {
            var right = ast( new ClassExpression(type(rtac.type(), false)), rtac.type() );
            return ast( binary(rtac.left, rtac.op, right), rtac );
        }

        if( ctx instanceof ShiftExprAltContext sac )
            return ast( shift(sac), sac );

        if( ctx instanceof UnaryAddExprAltContext uaac )
            return ast( unaryAdd(expression(uaac.expression()), uaac.op), uaac );

        if( ctx instanceof UnaryNotExprAltContext unac )
            return ast( unaryNot(expression(unac.expression()), unac.op), unac );

        throw createParsingFailedException("Invalid expression: " + ctx.getText(), ctx);
    }

    private Expression binary(ExpressionContext left, Token op, ExpressionContext right) {
        return binX(expression(left), token(op), expression(right));
    }

    private Expression binary(ExpressionContext left, Token op, Expression right) {
        return binX(expression(left), token(op), right);
    }

    private Expression shift(ShiftExprAltContext ctx) {
        var left = expression(ctx.left);
        var right = expression(ctx.right);

        if( ctx.riOp != null )
            return new RangeExpression(left, right, true);
        if( ctx.reOp != null )
            return new RangeExpression(left, right, false, true);

        org.codehaus.groovy.syntax.Token op = null;
        if( ctx.dlOp != null )
            op = token(ctx.dlOp, 2);
        if( ctx.dgOp != null )
            op = token(ctx.dgOp, 2);
        if( ctx.tgOp != null )
            op = token(ctx.tgOp, 3);

        return binX(left, op, right);
    }

    private Expression ternary(ConditionalExprAltContext ctx) {
        if( ctx.ELVIS() != null )
            return elvisX(expression(ctx.condition), expression(ctx.fb));

        var condition = ast( boolX(expression(ctx.condition)), ctx.condition );
        return ternaryX(condition, expression(ctx.tb), expression(ctx.fb));
    }

    private Expression unaryAdd(Expression expression, Token op) {
        if( op.getType() == ConfigParser.ADD )
            return new UnaryPlusExpression(expression);

        if( op.getType() == ConfigParser.SUB )
            return new UnaryMinusExpression(expression);

        throw new IllegalStateException();
    }

    private Expression unaryNot(Expression expression, Token op) {
        if( op.getType() == ConfigParser.NOT )
            return new NotExpression(expression);

        if( op.getType() == ConfigParser.BITNOT )
            return new BitwiseNegationExpression(expression);

        throw new IllegalStateException();
    }

    /// -- PATH EXPRESSIONS

    private Expression pathExpression(PathExprAltContext ctx) {
        try {
            var primary = primary(ctx.primary());
            return (Expression) ctx.pathElement().stream()
                .map(el -> (Object) el)
                .reduce(primary, (acc, el) -> pathElement((Expression) acc, (PathElementContext) el));
        }
        catch( IllegalStateException e ) {
            throw createParsingFailedException("Invalid expression: " + ctx.getText(), ctx);
        }
    }

    private Expression pathElement(Expression expression, PathElementContext ctx) {
        if( ctx instanceof PropertyPathExprAltContext pac )
            return ast( pathPropertyElement(expression, pac), expression, pac );

        if( ctx instanceof ClosurePathExprAltContext cac ) {
            var closure = closure(cac.closure());
            return ast( pathClosureElement(expression, closure), expression, cac );
        }

        if( ctx instanceof ArgumentsPathExprAltContext aac )
            return ast( pathArgumentsElement(expression, aac.arguments()), expression, aac );

        if( ctx instanceof IndexPathExprAltContext iac )
            return ast( pathIndexElement(expression, iac.indexPropertyArgs()), expression, iac );

        throw new IllegalStateException();
    }

    private Expression pathPropertyElement(Expression expression, PropertyPathExprAltContext ctx) {
        var property = ast( constX(namePart(ctx.namePart())), ctx.namePart() );
        var safe = ctx.SAFE_DOT() != null || ctx.SPREAD_DOT() != null;
        var result = new PropertyExpression(expression, property, safe);
        if( ctx.SPREAD_DOT() != null )
            result.setSpreadSafe(true);
        return result;
    }

    private String namePart(NamePartContext ctx) {
        if( ctx.keywords() != null )
            return keywords(ctx.keywords());

        if( ctx.identifier() != null )
            return identifier(ctx.identifier());

        if( ctx.stringLiteral() != null )
            return stringLiteral(ctx.stringLiteral());

        throw new IllegalStateException();
    }

    private Expression pathClosureElement(Expression expression, Expression closure) {
        if( expression instanceof MethodCallExpression mce ) {
            // normal arguments, e.g. 1, 2
            if ( !(mce.getArguments() instanceof ArgumentListExpression) )
                throw new IllegalStateException();

            var arguments = (ArgumentListExpression) mce.getArguments();
            arguments.addExpression(closure);
            return mce;

            // TODO: only needed if namedArgs uses TupleExpression
            // named arguments, e.g. x: 1, y: 2
            // if ( arguments instanceof TupleExpression te ) {
            //     if( !te.expressions )
            //         throw new IllegalStateException()
            //     var namedArguments = (NamedArgumentListExpression) te.getExpression(0)
            //     mce.getArguments() = args( mapX(namedArguments.mapEntryExpressions), closure )
            //     return mce
            // }
        }

        var arguments = ast( args(closure), closure );

        // e.g. obj.m { }
        if( expression instanceof PropertyExpression pe )
            return propMethodCall(pe, arguments);

        // e.g. m { }, "$m" { }, "m" { }
        if( isConstMethodName(expression) )
            return thisMethodCall(expression, arguments);

        // e.g. <expr> { } -> <expr>.call { }
        return callMethodCall(expression, arguments);
    }

    private Expression pathArgumentsElement(Expression caller, ArgumentsContext ctx) {
        var arguments = argumentList(ctx.argumentList());
        return ast( methodCall(caller, arguments), caller, ctx );
    }

    private Expression pathIndexElement(Expression expression, IndexPropertyArgsContext ctx) {
        var elements = expressionList(ctx.expressionList());

        Expression index;
        if( elements.size() > 1 ) {
            // e.g. a[1, 2]
            var list = listX(elements);
            list.setWrapped(true);
            index = list;
        }
        else if( elements.get(0) instanceof SpreadExpression ) {
            // e.g. a[*[1, 2]]
            var list = listX(elements);
            list.setWrapped(false);
            index = list;
        }
        else {
            // e.g. a[1]
            index = elements.get(0);
        }

        return indexX(expression, ast(index, ctx));
    }

    /// -- PRIMARY EXPRESSIONS

    private Expression primary(PrimaryContext ctx) {
        if( ctx instanceof IdentifierPrmrAltContext iac )
            return ast( varX(identifier(iac.identifier())), iac );

        if( ctx instanceof LiteralPrmrAltContext lac )
            return ast( literal(lac.literal()), lac );

        if( ctx instanceof GstringPrmrAltContext gac )
            return ast( gstring(gac.gstring()), gac );

        if( ctx instanceof NewPrmrAltContext nac )
            return ast( creator(nac.creator()), nac );

        if( ctx instanceof ParenPrmrAltContext pac )
            return ast( parExpression(pac.parExpression()), pac );

        if( ctx instanceof ClosurePrmrAltContext cac )
            return ast( closure(cac.closure()), cac );

        if( ctx instanceof ListPrmrAltContext lac )
            return ast( list(lac.list()), lac );

        if( ctx instanceof MapPrmrAltContext mac )
            return ast( map(mac.map()), mac );

        if( ctx instanceof BuiltInTypePrmrAltContext bac )
            return ast( builtInType(bac.builtInType()), bac );

        throw createParsingFailedException("Invalid expression: " + ctx.getText(), ctx);
    }

    private Expression builtInType(BuiltInTypeContext ctx) {
        return varX(ctx.getText());
    }

    private String identifier(IdentifierContext ctx) {
        return ctx.getText();
    }

    private String keywords(KeywordsContext ctx) {
        return ctx.getText();
    }

    private Expression literal(LiteralContext ctx) {
        if( ctx instanceof IntegerLiteralAltContext iac )
            return ast( integerLiteral(iac), iac );

        if( ctx instanceof FloatingPointLiteralAltContext fac )
            return ast( floatingPointLiteral(fac), fac );

        if( ctx instanceof StringLiteralAltContext sac )
            return ast( string(sac.stringLiteral()), sac );

        if( ctx instanceof BooleanLiteralAltContext bac )
            return ast( constX("true".equals(bac.getText())), bac );

        if( ctx instanceof NullLiteralAltContext nac )
            return ast( constX(null), nac );

        throw createParsingFailedException("Invalid expression: " + ctx.getText(), ctx);
    }

    private Expression integerLiteral(IntegerLiteralAltContext ctx) {
        Number num = null;
        try {
            num = Numbers.parseInteger(ctx.getText());
        }
        catch( Exception e ) {
            numberFormatError = new Tuple2(ctx, e);
        }

        return constX(num, true);
    }

    private Expression floatingPointLiteral(FloatingPointLiteralAltContext ctx) {
        Number num = null;
        try {
            num = Numbers.parseDecimal(ctx.getText());
        }
        catch( Exception e ) {
            numberFormatError = new Tuple2(ctx, e);
        }

        return constX(num, true);
    }

    private Expression string(StringLiteralContext ctx) {
        var text = ctx.getText();
        var result = constX(stringLiteral(text));
        if( text.startsWith(SQ_STR)    ) result.putNodeMetaData(QUOTE_CHAR, SQ_STR);
        if( text.startsWith(DQ_STR)    ) result.putNodeMetaData(QUOTE_CHAR, DQ_STR);
        if( text.startsWith(TSQ_STR)   ) result.putNodeMetaData(QUOTE_CHAR, TSQ_STR);
        if( text.startsWith(TDQ_STR)   ) result.putNodeMetaData(QUOTE_CHAR, TDQ_STR);
        if( text.startsWith(SLASH_STR) ) result.putNodeMetaData(QUOTE_CHAR, SLASH_STR);
        return result;
    }

    private String stringLiteral(StringLiteralContext ctx) {
        return stringLiteral(ctx.getText());
    }

    private String stringLiteral(String text) {
        var startsWithSlash = text.startsWith(SLASH_STR);

        if( text.startsWith(TSQ_STR) || text.startsWith(TDQ_STR) ) {
            text = StringUtils.removeCR(text);
            text = StringUtils.trimQuotations(text, 3);
        }
        else if( text.startsWith(SQ_STR) || text.startsWith(DQ_STR) || startsWithSlash ) {
            // the slashy string can span rows, so we have to remove CR for it
            if( startsWithSlash )
                text = StringUtils.removeCR(text);
            text = StringUtils.trimQuotations(text, 1);
        }

        var slashyType = startsWithSlash
            ? StringUtils.SLASHY
            : StringUtils.NONE_SLASHY;

        return StringUtils.replaceEscapes(text, slashyType);
    }

    private Expression gstring(GstringContext ctx) {
        var text = ctx.getText();
        var verbatimText = stringLiteral(text);
        var strings = new ArrayList<ConstantExpression>();
        var values = new ArrayList<Expression>();

        for( var part : ctx.gstringDqPart() ) {
            if( part instanceof GstringDqTextAltContext tac )
                strings.add(ast( constX(tac.getText()), tac ));

            if( part instanceof GstringDqPathAltContext pac )
                values.add(ast( gstringPath(pac), pac ));

            if( part instanceof GstringDqExprAltContext eac )
                values.add(expression(eac.expression()));
        }

        for( var part : ctx.gstringTdqPart() ) {
            if( part instanceof GstringTdqTextAltContext tac )
                strings.add(ast( constX(tac.getText()), tac ));

            if( part instanceof GstringTdqPathAltContext pac )
                values.add(ast( gstringPath(pac), pac ));

            if( part instanceof GstringTdqExprAltContext eac )
                values.add(expression(eac.expression()));
        }

        var result = new GStringExpression(verbatimText, strings, values);
        if( text.startsWith(DQ_STR)  ) result.putNodeMetaData(QUOTE_CHAR, DQ_STR);
        if( text.startsWith(TDQ_STR) ) result.putNodeMetaData(QUOTE_CHAR, TDQ_STR);
        return result;
    }

    private Expression gstringPath(ParserRuleContext ctx) {
        var names = ctx.getText().split("\\.");
        int currentLine = ctx.getStart().getLine();
        int currentChar = ctx.getStart().getCharPositionInLine() + 1;
        var varName = names[0].substring(1);
        Expression result = varX(varName);
        currentChar += 1;
        result.setLineNumber(currentLine);
        result.setColumnNumber(currentChar);
        currentChar += varName.length();
        result.setLastLineNumber(currentLine);
        result.setLastColumnNumber(currentChar);

        for( int i = 1; i < names.length; i++ ) {
            var propName = names[i];
            var property = constX(propName);
            currentChar += 1;
            property.setLineNumber(currentLine);
            property.setColumnNumber(currentChar);
            currentChar += propName.length();
            property.setLastLineNumber(currentLine);
            property.setLastColumnNumber(currentChar);
            result = ast( propX(result, property), result, property );
        }

        return result;
    }

    private Expression creator(CreatorContext ctx) {
        var type = createdName(ctx.createdName());
        var arguments = argumentList(ctx.arguments().argumentList());
        return ctorX(type, arguments);
    }

    private Expression parExpression(ParExpressionContext ctx) {
        var expression = expression(ctx.expression());
        expression.getNodeMetaData(INSIDE_PARENTHESES_LEVEL, k -> new AtomicInteger()).getAndAdd(1);
        return expression;
    }

    private Expression closure(ClosureContext ctx) {
        var params = parameters(ctx.formalParameterList());
        var code = blockStatements(ctx.blockStatements());
        return ast( closureX(params, code), ctx );
    }

    private Expression list(ListContext ctx) {
        if( ctx.COMMA() != null && ctx.expressionList() == null )
            throw createParsingFailedException("Empty list literal should not contain any comma(,)", ctx.COMMA());

        return listX(expressionList(ctx.expressionList()));
    }

    private List<Expression> expressionList(ExpressionListContext ctx) {
        if( ctx == null )
            return Collections.emptyList();
        
        return ctx.expressionListElement().stream()
            .map(this::listElement)
            .collect(Collectors.toList());
    }

    private Expression listElement(ExpressionListElementContext ctx) {
        var element = expression(ctx.expression());
        return ctx.MUL() != null
            ? ast( new SpreadExpression(element), ctx )
            : ast( element, ctx );
    }

    private Expression map(MapContext ctx) {
        if( ctx.mapEntryList() == null )
            return new MapExpression();

        var entries = ctx.mapEntryList().mapEntry().stream()
            .map(this::mapEntry)
            .collect(Collectors.toList());
        return mapX(entries);
    }

    private MapEntryExpression mapEntry(MapEntryContext ctx) {
        var value = expression(ctx.expression());
        var key = ctx.MUL() != null
            ? ast( new SpreadMapExpression(value), ctx )
            : mapEntryLabel(ctx.mapEntryLabel());
        return ast( entryX(key, value), ctx );
    }

    private Expression mapEntryLabel(MapEntryLabelContext ctx) {
        if( ctx.keywords() != null )
            return ast( constX(keywords(ctx.keywords())), ctx );

        if( ctx.primary() != null ) {
            var expression = primary(ctx.primary());
            return expression instanceof VariableExpression ve && !isInsideParentheses(ve)
                ? ast( constX(ve.getName()), ve )
                : ast( expression, ctx );
        }

        throw createParsingFailedException("Unsupported map entry label: " + ctx.getText(), ctx);
    }

    private Expression methodCall(Expression caller, Expression arguments) {
        // e.g. (obj.x)(), (obj.@x)()
        if( isInsideParentheses(caller) )
            return callMethodCall(caller, arguments);

        // e.g. obj.a(1, 2)
        if( caller instanceof PropertyExpression pe )
            return propMethodCall(pe, arguments);

        // e.g. m(), "$m"(), "m"()
        if( isConstMethodName(caller) )
            return thisMethodCall(caller, arguments);

        // e.g. <expr>(<args>) -> <expr>.call(<args>)
        return callMethodCall(caller, arguments);
    }

    private boolean isConstMethodName(Expression caller) {
        if( caller instanceof VariableExpression )
            return true;
        if( caller instanceof ConstantExpression ce && ce.getValue() instanceof String )
            return true;
        return false;
    }

    private Expression propMethodCall(PropertyExpression caller, Expression arguments) {
        var result = callX(caller.getObjectExpression(), caller.getProperty(), arguments);
        result.setImplicitThis(false);
        result.setSafe(caller.isSafe());
        result.setSpreadSafe(caller.isSpreadSafe());

        // method call obj*.m() -> safe=false and spreadSafe=true
        // property access obj*.p -> safe=true and spreadSafe=true
        if( caller.isSpreadSafe() )
            result.setSafe(false);

        return ast( result, caller, arguments );
    }

    private Expression thisMethodCall(Expression caller, Expression arguments) {
        var object = varX("this");
        object.setColumnNumber(caller.getColumnNumber());
        object.setLineNumber(caller.getLineNumber());

        var name = caller instanceof VariableExpression ve
            ? ast( constX(ve.getText()), ve )
            : caller;

        return ast( callX(object, name, arguments), caller, arguments );
    }

    private Expression callMethodCall(Expression caller, Expression arguments) {
        var call = callX(caller, CALL_STR, arguments);
        call.setImplicitThis(false);
        return ast( call, caller, arguments );
    }

    private Expression argumentList(ArgumentListContext ctx) {
        if( ctx == null )
            return new ArgumentListExpression();

        var arguments = new ArrayList<Expression>();
        var opts = new ArrayList<MapEntryExpression>();

        for( var el : ctx.argumentListElement() ) {
            if( el.expressionListElement() != null )
                arguments.add(listElement(el.expressionListElement()));

            else if( el.namedArg() != null )
                opts.add(namedArg(el.namedArg()));

            else
                throw createParsingFailedException("Invalid method argument: " + ctx.getText(), ctx);
        }

        // TODO: validate duplicate named arguments ?
        // TODO: only named arguments -> TupleExpression ?
        if( !opts.isEmpty() )
            arguments.add(0, mapX(opts));

        var result = ast( args(arguments), ctx );
        if( !opts.isEmpty() )
            result.putNodeMetaData(HAS_NAMED_ARGS, true);
        return result;
    }

    private MapEntryExpression namedArg(NamedArgContext ctx) {
        var value = expression(ctx.expression());
        var key = ctx.MUL() != null
            ? new SpreadMapExpression(value)
            : ast( namedArgLabel(ctx.namedArgLabel()), ctx.namedArgLabel() );
        return ast( new MapEntryExpression(key, value), ctx );
    }

    private Expression namedArgLabel(NamedArgLabelContext ctx) {
        if( ctx.keywords() != null )
            return constX(keywords(ctx.keywords()));

        if( ctx.identifier() != null )
            return constX(identifier(ctx.identifier()));

        if( ctx.literal() != null )
            return literal(ctx.literal());

        if( ctx.gstring() != null )
            return gstring(ctx.gstring());

        throw createParsingFailedException("Invalid method named argument: " + ctx.getText(), ctx);
    }

    /// MISCELLANEOUS

    private Parameter[] parameters(FormalParameterListContext ctx) {
        // NOTE: implicit `it` is not allowed
        if( ctx == null )
            return null;

        for( int i = 0, n = ctx.formalParameter().size(); i < n - 1; i += 1 ) {
            var el = ctx.formalParameter(i);
            if( el.ELLIPSIS() != null )
                throw createParsingFailedException("The var-arg parameter must be the last parameter", el);
        }

        var params = ctx.formalParameter().stream()
            .map(this::parameter)
            .collect(Collectors.toList());
        for( int n = params.size(), i = n - 1; i >= 0; i -= 1 ) {
            var param = params.get(i);
            for( var other : params ) {
                if( other == param )
                    continue;
                if( other.getName().equals(param.getName()) )
                    throw createParsingFailedException("Duplicated parameter '" + param.getName() + "' found", param);
            }
        }

        return params.toArray(Parameter.EMPTY_ARRAY);
    }

    private Parameter parameter(FormalParameterContext ctx) {
        ClassNode type = type(ctx.type());
        if( ctx.ELLIPSIS() != null ) {
            type = type.makeArray();
            type = ctx.type() != null
                ? ast( type, ctx.type(), ast(constX("..."), ctx.ELLIPSIS()) )
                : ast( type, ctx.ELLIPSIS() );
        }

        var name = identifier(ctx.identifier());
        var defaultValue = ctx.expression() != null
            ? expression(ctx.expression())
            : null;
        return ast( param(type, name, defaultValue), ctx );
    }

    private org.codehaus.groovy.syntax.Token token(Token token) {
        return token(token, 1);
    }

    private org.codehaus.groovy.syntax.Token token(Token token, int cardinality) {
        var tokenText = token.getText();
        var tokenType = token.getType();
        var text = cardinality == 1
            ? tokenText
            : StringGroovyMethods.multiply(tokenText, cardinality);
        var type = tokenType == RANGE_EXCLUSIVE_RIGHT || tokenType == RANGE_INCLUSIVE
            ? Types.RANGE_OPERATOR
            : Types.lookup(text, Types.ANY);
        return new org.codehaus.groovy.syntax.Token( type, text, token.getLine(), token.getCharPositionInLine() + 1 );
    }

    private ClassNode createdName(CreatedNameContext ctx) {
        if( ctx.qualifiedClassName() != null ) {
            var classNode = qualifiedClassName(ctx.qualifiedClassName());
            if( ctx.typeArguments() != null )
                classNode.setGenericsTypes( typeArguments(ctx.typeArguments()) );
            return classNode;
        }

        if( ctx.primitiveType() != null )
            return primitiveType(ctx.primitiveType());

        throw createParsingFailedException("Unrecognized created name: " + ctx.getText(), ctx);
    }

    private ClassNode primitiveType(PrimitiveTypeContext ctx) {
        var classNode = ClassHelper.make(ctx.getText()).getPlainNodeReference(false);
        return ast( classNode, ctx );
    }

    private ClassNode qualifiedClassName(QualifiedClassNameContext ctx) {
        return qualifiedClassName(ctx, true);
    }

    private ClassNode qualifiedClassName(QualifiedClassNameContext ctx, boolean allowProxy) {
        var text = ctx.getText();
        var classNode = ClassHelper.make(text);
        if( text.contains(".") )
            classNode.putNodeMetaData(IS_FULLY_QUALIFIED, true);

        if( classNode.isUsingGenerics() && allowProxy ) {
            var proxy = ClassHelper.makeWithoutCaching(classNode.getName());
            proxy.setRedirect(classNode);
            return proxy;
        }

        return ast( classNode, ctx );
    }

    private ClassNode type(TypeContext ctx, boolean allowProxy) {
        if( ctx == null )
            return ClassHelper.dynamicType();

        if( ctx.qualifiedClassName() != null ) {
            var classNode = qualifiedClassName(ctx.qualifiedClassName(), allowProxy);
            if( ctx.typeArguments() != null )
                classNode.setGenericsTypes( typeArguments(ctx.typeArguments()) );
            return classNode;
        }

        if( ctx.primitiveType() != null )
            return primitiveType(ctx.primitiveType());

        throw createParsingFailedException("Unrecognized type: " + ctx.getText(), ctx);
    }

    private ClassNode type(TypeContext ctx) {
        return type(ctx, true);
    }

    private GenericsType[] typeArguments(TypeArgumentsContext ctx) {
        return ctx.type().stream()
            .map(this::genericsType)
            .toArray(GenericsType[]::new);
    }

    private GenericsType genericsType(TypeContext ctx) {
        return ast( new GenericsType(type(ctx)), ctx );
    }

    /// HELPERS

    private boolean isInsideParentheses(NodeMetaDataHandler nodeMetaDataHandler) {
        Number insideParenLevel = nodeMetaDataHandler.getNodeMetaData(INSIDE_PARENTHESES_LEVEL);
        return insideParenLevel != null && insideParenLevel.intValue() > 0;
    }

    private CompilationFailedException createParsingFailedException(String msg, ParserRuleContext ctx) {
        return createParsingFailedException(
            new SyntaxException(msg,
                ctx.start.getLine(),
                ctx.start.getCharPositionInLine() + 1,
                ctx.stop.getLine(),
                ctx.stop.getCharPositionInLine() + 1 + ctx.stop.getText().length()));
    }

    private CompilationFailedException createParsingFailedException(String msg, Tuple2<Integer,Integer> start, Tuple2<Integer,Integer> end) {
        return createParsingFailedException(
            new SyntaxException(msg,
                start.getV1(),
                start.getV2(),
                end.getV1(),
                end.getV2()));
    }

    private CompilationFailedException createParsingFailedException(String msg, ASTNode node) {
        return createParsingFailedException(
            new SyntaxException(msg,
                node.getLineNumber(),
                node.getColumnNumber(),
                node.getLastLineNumber(),
                node.getLastColumnNumber()));
    }

    private CompilationFailedException createParsingFailedException(String msg, TerminalNode node) {
        return createParsingFailedException(msg, node.getSymbol());
    }

    private CompilationFailedException createParsingFailedException(String msg, Token token) {
        return createParsingFailedException(
            new SyntaxException(msg,
                token.getLine(),
                token.getCharPositionInLine() + 1,
                token.getLine(),
                token.getCharPositionInLine() + 1 + token.getText().length()));
    }

    private CompilationFailedException createParsingFailedException(Throwable t) {
        if( t instanceof SyntaxException se )
            collectSyntaxError(se);

        else if( t instanceof GroovySyntaxError gse )
            collectSyntaxError(
                    new SyntaxException(
                            gse.getMessage(),
                            gse,
                            gse.getLine(),
                            gse.getColumn()));

        else if( t instanceof Exception e )
            collectException(e);

        return new CompilationFailedException(
                CompilePhase.PARSING.getPhaseNumber(),
                sourceUnit,
                t);
    }

    private void collectSyntaxError(SyntaxException e) {
        sourceUnit.getErrorCollector().addFatalError(new SyntaxErrorMessage(e, sourceUnit));
    }

    private void collectException(Exception e) {
        sourceUnit.getErrorCollector().addException(e, sourceUnit);
    }

    private void removeErrorListeners() {
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
    }

    private void addErrorListeners() {
        lexer.addErrorListener(createANTLRErrorListener());
        parser.addErrorListener(createANTLRErrorListener());
    }

    private ANTLRErrorListener createANTLRErrorListener() {
        return new ANTLRErrorListener() {
            @Override
            public void syntaxError(Recognizer recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
                collectSyntaxError(new SyntaxException(msg, line, charPositionInLine + 1));
            }

            @Override
            public void reportAmbiguity(Parser recognizer, DFA dfa, int startIndex, int stopIndex, boolean exact, BitSet ambigAlts, ATNConfigSet configs) {}

            @Override
            public void reportAttemptingFullContext(Parser recognizer, DFA dfa, int startIndex, int stopIndex, BitSet conflictingAlts, ATNConfigSet configs) {}

            @Override
            public void reportContextSensitivity(Parser recognizer, DFA dfa, int startIndex, int stopIndex, int prediction, ATNConfigSet configs) {}
        };
    }

    private static final String CALL_STR = "call";
    private static final String SLASH_STR = "/";
    private static final String TDQ_STR = "\"\"\"";
    private static final String TSQ_STR = "'''";
    private static final String SQ_STR = "'";
    private static final String DQ_STR = "\"";

    private static final String HAS_NAMED_ARGS = "_HAS_NAMED_ARSG";
    private static final String INSIDE_PARENTHESES_LEVEL = "_INSIDE_PARENTHESES_LEVEL";
    private static final String IS_FULLY_QUALIFIED = "_IS_FULLY_QUALIFIED";
    private static final String QUOTE_CHAR = "_QUOTE_CHAR";

}
