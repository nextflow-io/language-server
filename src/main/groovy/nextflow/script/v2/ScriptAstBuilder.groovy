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
package nextflow.script.v2

import java.util.concurrent.atomic.AtomicInteger

import com.google.common.hash.Hashing
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.antlr.ScriptLexer
import nextflow.antlr.ScriptParser
import nextflow.antlr.DescriptiveErrorStrategy
import org.antlr.v4.runtime.ANTLRErrorListener
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token as ParserToken
import org.antlr.v4.runtime.atn.ATNConfigSet
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.dfa.DFA
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.antlr.v4.runtime.tree.TerminalNode
import org.apache.groovy.parser.antlr4.GroovySyntaxError
import org.apache.groovy.parser.antlr4.util.StringUtils
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.GenericsType
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.NodeMetaDataHandler
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.NotExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.SpreadMapExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.expr.UnaryPlusExpression
import org.codehaus.groovy.ast.stmt.AssertStatement
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.Numbers
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types

import static nextflow.antlr.ScriptParser.*
import static nextflow.antlr.PositionConfigureUtils.configureAST as ast
import static org.codehaus.groovy.ast.tools.GeneralUtils.*

/**
 * Transform a Nextflow script parse tree into a Groovy AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
class ScriptAstBuilder {

    private SourceUnit sourceUnit
    private ScriptNode moduleNode
    private ScriptLexer lexer
    private ScriptParser parser
    private final GroovydocManager groovydocManager

    private Tuple2<ParserRuleContext,Exception> numberFormatError

    ScriptAstBuilder(SourceUnit sourceUnit, boolean groovydocEnabled) {
        this.sourceUnit = sourceUnit
        this.moduleNode = new ScriptNode(sourceUnit)

        final charStream = createCharStream(sourceUnit)
        this.lexer = new ScriptLexer(charStream)
        this.parser = new ScriptParser(new CommonTokenStream(lexer))
        parser.setErrorHandler(new DescriptiveErrorStrategy(charStream))

        this.groovydocManager = new GroovydocManager(groovydocEnabled)
    }

    private CharStream createCharStream(SourceUnit sourceUnit) {
        try {
            return CharStreams.fromReader(
                    new BufferedReader(sourceUnit.getSource().getReader()),
                    sourceUnit.getName())
        }
        catch( IOException e ) {
            throw new RuntimeException("Error occurred when reading source code.", e)
        }
    }

    private CompilationUnitContext buildCST() {
        try {
            final tokenStream = parser.getInputStream()
            try {
                return buildCST(PredictionMode.SLL)
            }
            catch( Throwable t ) {
                // if some syntax error occurred in the lexer, no need to retry the powerful LL mode
                if( t instanceof GroovySyntaxError && t.getSource() == GroovySyntaxError.LEXER )
                    throw t

                log.trace "Parsing mode SLL failed, falling back to LL"
                tokenStream.seek(0)
                return buildCST(PredictionMode.LL)
            }
        }
        catch( Throwable t ) {
            throw convertException(t)
        }
    }

    private CompilationUnitContext buildCST(PredictionMode predictionMode) {
        parser.getInterpreter().setPredictionMode(predictionMode)

        removeErrorListeners()
        if( predictionMode == PredictionMode.LL )
            addErrorListeners()

        return parser.compilationUnit()
    }

    private CompilationFailedException convertException(Throwable t) {
        if( t instanceof CompilationFailedException )
            return t
        else if( t instanceof ParseCancellationException )
            return createParsingFailedException(t.getCause())
        else
            return createParsingFailedException(t)
    }

    ModuleNode buildAST(SourceUnit sourceUnit) {
        try {
            return compilationUnit(buildCST())
        }
        catch( Throwable t ) {
            throw convertException(t)
        }
    }

    /// SCRIPT STATEMENTS

    private ModuleNode compilationUnit(CompilationUnitContext ctx) {
        for( final stmt : ctx.scriptStatement() )
            scriptStatement(stmt)

        if( ctx.workflowMain() ) {
            final workflowNode = workflowDef(ctx.workflowMain())
            moduleNode.addWorkflow(workflowNode)
            moduleNode.setEntry(workflowNode)
        }

        final scriptClassNode = moduleNode.getScriptClassDummy()
        scriptClassNode.setName(getMainClassName())

        final statements = moduleNode.getStatementBlock().getStatements()
        if( scriptClassNode && !statements.isEmpty() ) {
            final first = statements.first()
            final last = statements.last()
            scriptClassNode.setSourcePosition(first)
            scriptClassNode.setLastColumnNumber(last.getLastColumnNumber())
            scriptClassNode.setLastLineNumber(last.getLastLineNumber())
        }

        if( numberFormatError != null )
            throw createParsingFailedException(numberFormatError.getV2().getMessage(), numberFormatError.getV1())

        return moduleNode
    }

    private String getMainClassName() {
        final text = sourceUnit.getSource().getReader().getText()
        final hash = Hashing.sipHash24().newHasher().putUnencodedChars(text).hash()
        return '_nf_script_' + hash.toString()
    }

    private void scriptStatement(ScriptStatementContext ctx) {
        if( ctx instanceof FeatureFlagStmtAltContext ) {
            moduleNode.addFeatureFlag(featureFlag(ctx.featureFlag()))
        }

        else if( ctx instanceof FunctionDefAltContext ) {
            moduleNode.addFunction(functionDef(ctx.functionDef()))
        }

        else if( ctx instanceof IncludeStmtAltContext ) {
            moduleNode.addInclude(includeStatement(ctx.includeStatement()))
        }

        else if( ctx instanceof OutputDefAltContext ) {
            final outputNode = outputDef(ctx.outputDef())
            if( moduleNode.output != null )
                collectSyntaxError(new SyntaxException('Output block defined more than once', outputNode))
            moduleNode.setOutput(outputNode)
        }

        else if( ctx instanceof ProcessDefAltContext ) {
            moduleNode.addProcess(processDef(ctx.processDef()))
        }

        else if( ctx instanceof WorkflowDefAltContext ) {
            final workflowNode = workflowDef(ctx.workflowDef())
            if( !workflowNode.name ) {
                if( moduleNode.entry != null )
                    collectSyntaxError(new SyntaxException('Entry workflow defined more than once', workflowNode))
                moduleNode.setEntry(workflowNode)
            }
            moduleNode.addWorkflow(workflowNode)
        }

        else if( ctx instanceof IncompleteScriptStmtAltContext ) {
            incompleteScriptStatement(ctx.incompleteScriptStatement())
        }

        else
            throw createParsingFailedException("Invalid statement: ${ctx.text}", ctx)
    }

    private FeatureFlagNode featureFlag(FeatureFlagContext ctx) {
        final names = ctx.featureFlagPath().identifier().collect( this.&identifier )
        final value = literal(ctx.literal())

        ast( new FeatureFlagNode(names.join('.'), value), ctx )
    }

    private IncludeNode includeStatement(IncludeStatementContext ctx) {
        final source = stringLiteral(ctx.stringLiteral())
        final modules = ctx.includeNames().includeName().collect { it ->
            final name = it.name.text
            final alias = it.alias?.text
            ast( new IncludeVariable(name, alias), it )
        }

        ast( new IncludeNode(source, modules), ctx )
    }

    private ProcessNode processDef(ProcessDefContext ctx) {
        final name = ctx.name.text
        final directives = processDirectives(ctx.body.processDirectives())
        final inputs = processInputs(ctx.body.processInputs())
        final outputs = processOutputs(ctx.body.processOutputs())
        final when = processWhen(ctx.body.processWhen())
        final type = processType(ctx.body.processExec())
        final exec = blockStatements(ctx.body.blockStatements() ?: ctx.body.processExec().blockStatements())
        final stub = processStub(ctx.body.processStub())

        final result = ast( new ProcessNode(name, directives, inputs, outputs, when, type, exec, stub), ctx )
        groovydocManager.handle(result, ctx)
        return result
    }

    private Statement processDirectives(ProcessDirectivesContext ctx) {
        if( !ctx )
            return EmptyStatement.INSTANCE
        return ast( block(null, ctx.processDirective().collect(this.&processDirective)), ctx )
    }

    private Statement processDirective(ProcessDirectiveContext ctx) {
        return checkDirective(statement(ctx.statement()), 'Invalid process directive')
    }

    private Statement processInputs(ProcessInputsContext ctx) {
        if( !ctx )
            return EmptyStatement.INSTANCE
        return ast( block(null, ctx.processDirective().collect(this.&processInput)), ctx )
    }

    private Statement processInput(ProcessDirectiveContext ctx) {
        return checkDirective(statement(ctx.statement()), 'Invalid process input')
    }

    private Statement processOutputs(ProcessOutputsContext ctx) {
        if( !ctx )
            return EmptyStatement.INSTANCE
        return ast( block(null, ctx.processDirective().collect(this.&processOutput)), ctx )
    }

    private Statement processOutput(ProcessDirectiveContext ctx) {
        return checkDirective(statement(ctx.statement()), 'Invalid process output')
    }

    private Statement checkDirective(Statement stmt, String errorMessage) {
        if( stmt !instanceof ExpressionStatement ) {
            collectSyntaxError(new SyntaxException(errorMessage, stmt))
            return ast( new EmptyStatement(), stmt )
        }

        final expression = ((ExpressionStatement) stmt).expression
        if( expression instanceof VariableExpression ) {
            final method = ast( constX(expression.name), expression )
            stmt.expression = ast( callX(varX('this'), method, new ArgumentListExpression()), stmt )
            return stmt
        }
        if( isDirectiveWithNegativeValue(expression) ) {
            final binary = (BinaryExpression) expression
            final left = (VariableExpression) binary.leftExpression
            final method = ast( constX(left.name), left )
            final value = (Expression) ast( new UnaryMinusExpression(binary.rightExpression), binary.rightExpression )
            final arguments = ast( args(value), value )
            stmt.expression = ast( callX(varX('this'), method, arguments), stmt )
            return stmt
        }
        if( expression !instanceof MethodCallExpression ) {
            collectSyntaxError(new SyntaxException(errorMessage, stmt))
            return ast( new EmptyStatement(), stmt )
        }

        final call = (MethodCallExpression) expression
        if( !call.isImplicitThis() || call.getMethod() !instanceof ConstantExpression ) {
            collectSyntaxError(new SyntaxException(errorMessage, stmt))
            return ast( new EmptyStatement(), stmt )
        }

        return stmt
    }

    private boolean isDirectiveWithNegativeValue(Expression expression) {
        if( expression !instanceof BinaryExpression )
            return false
        final binary = (BinaryExpression) expression
        if( binary.leftExpression !instanceof VariableExpression )
            return false
        if( binary.operation.type != Types.MINUS )
            return false
        return true
    }

    private Expression processWhen(ProcessWhenContext ctx) {
        if( !ctx )
            return EmptyExpression.INSTANCE
        return ast( expression(ctx.expression()), ctx )
    }

    private String processType(ProcessExecContext ctx) {
        if( !ctx )
            return 'script'

        if( ctx.EXEC() )
            return 'exec'
        else if( ctx.SHELL() )
            return 'shell'
        else
            return 'script'
    }

    private Statement processStub(ProcessStubContext ctx) {
        if( !ctx )
            return EmptyStatement.INSTANCE
        return ast( blockStatements(ctx.blockStatements()), ctx )
    }

    private WorkflowNode workflowDef(WorkflowDefContext ctx) {
        final name = ctx.name?.text
        final takes = workflowTakes(ctx.body?.workflowTakes())
        final emits = workflowEmits(ctx.body?.workflowEmits())
        final publishers = workflowPublishers(ctx.body?.workflowPublishers())
        final main = blockStatements(ctx.body?.workflowMain()?.blockStatements())

        final result = ast( new WorkflowNode(name, takes, emits, publishers, main), ctx )
        groovydocManager.handle(result, ctx)
        return result
    }

    private WorkflowNode workflowDef(WorkflowMainContext ctx) {
        final takes = EmptyStatement.INSTANCE
        final emits = EmptyStatement.INSTANCE
        final publishers = EmptyStatement.INSTANCE
        final main = blockStatements(ctx.blockStatements())
        for( final statement : main.statements ) {
            if( statement !instanceof ExpressionStatement )
                continue
            final stmtX = (ExpressionStatement)statement
            if( stmtX.expression !instanceof MethodCallExpression )
                continue
            final call = (MethodCallExpression)stmtX.expression
            final name = call.getMethodAsString()
            if( name == 'process' ) {
                collectSyntaxError(new SyntaxException("Process definition is not allowed with implicit workflow", statement))
                stmtX.expression = EmptyExpression.INSTANCE
            }
            else if( name == 'workflow' ) {
                collectSyntaxError(new SyntaxException("Workflow definition is not allowed with implicit workflow", statement))
                stmtX.expression = EmptyExpression.INSTANCE
            }
            else if( name == 'output' ) {
                collectSyntaxError(new SyntaxException("Output definition is not allowed with implicit workflow", statement))
                stmtX.expression = EmptyExpression.INSTANCE
            }
        }
        return new WorkflowNode(null, takes, emits, publishers, main)
    }

    private Statement workflowTakes(WorkflowTakesContext ctx) {
        if( !ctx )
            return EmptyStatement.INSTANCE

        final statements = ctx.identifier().collect(this.&workflowTake)
        return ast( block(null, statements), ctx )
    }

    private Statement workflowTake(IdentifierContext ctx) {
        return stmt(ast( varX(ctx.text), ctx ))
    }

    private Statement workflowEmits(WorkflowEmitsContext ctx) {
        if( !ctx )
            return EmptyStatement.INSTANCE

        final statements = ctx.workflowEmit()
            ? ctx.workflowEmit().collect(this.&workflowEmit)
            : List<Statement>.of(ast( stmt(expression(ctx.expression())), ctx ))
        return ast( block(null, statements), ctx )
    }

    private Statement workflowEmit(WorkflowEmitContext ctx) {
        final var = variableName(ctx.identifier())
        return ctx.expression()
            ? stmt(ast( assignX(var, expression(ctx.expression())), ctx ))
            : stmt(var)
    }

    private Statement workflowPublishers(WorkflowPublishersContext ctx) {
        if( !ctx )
            return EmptyStatement.INSTANCE

        final statements = ctx.workflowPublish().collect(this.&workflowPublish)
        return ast( block(null, statements), ctx )
    }

    private Statement workflowPublish(WorkflowPublishContext ctx) {
        final source = expression(ctx.source)
        final op = token(ctx.op, 2)
        final target = expression(ctx.target)
        return stmt(ast( binX(source, op, target), ctx ))
    }

    private OutputNode outputDef(OutputDefContext ctx) {
        final body = outputBody(ctx.outputBody())
        return ast( new OutputNode(body), ctx )
    }

    private Statement outputBody(OutputBodyContext ctx) {
        if( !ctx )
            return EmptyStatement.INSTANCE
        return ast( block(null, ctx.outputDirective().collect(this.&outputDirective)), ctx )
    }

    private Statement outputDirective(OutputDirectiveContext ctx) {
        return checkDirective(statement(ctx.statement()), 'Invalid output directive')
    }

    private FunctionNode functionDef(FunctionDefContext ctx) {
        final name = identifier(ctx.identifier())
        final returnType = type(ctx.type())
        final params = parameters(ctx.formalParameterList()) ?: [] as Parameter[]
        final code = blockStatements(ctx.blockStatements())

        final result = ast( new FunctionNode(name, returnType, params, code), ctx )
        groovydocManager.handle(result, ctx)
        return result
    }

    private Statement incompleteScriptStatement(IncompleteScriptStatementContext ctx) {
        final result = ast( new IncompleteNode(ctx.text), ctx )
        collectSyntaxError(new SyntaxException("Incomplete statement", result))
        return result
    }

    /// GROOVY STATEMENTS

    private Statement statement(StatementContext ctx) {
        if( ctx instanceof IfElseStmtAltContext )
            return ast( ifElseStatement(ctx.ifElseStatement()), ctx )

        if( ctx instanceof TryCatchStmtAltContext )
            return ast( tryCatchStatement(ctx.tryCatchStatement()), ctx )

        if( ctx instanceof ReturnStmtAltContext )
            return ast( returnStatement(ctx.expression()), ctx )

        if( ctx instanceof ThrowStmtAltContext )
            return ast( throwStatement(ctx.expression()), ctx )

        if( ctx instanceof AssertStmtAltContext )
            return ast( assertStatement(ctx.assertStatement()), ctx )

        if( ctx instanceof VariableDeclarationStmtAltContext )
            return ast( variableDeclaration(ctx.variableDeclaration()), ctx )

        if( ctx instanceof MultipleAssignmentStmtAltContext )
            return ast( assignment(ctx.multipleAssignmentStatement()), ctx )

        if( ctx instanceof AssignmentStmtAltContext )
            return ast( assignment(ctx.assignmentStatement()), ctx )

        if( ctx instanceof ExpressionStmtAltContext )
            return ast( expressionStatement(ctx.expressionStatement()), ctx )

        if( ctx instanceof EmptyStmtAltContext )
            return EmptyStatement.INSTANCE

        throw createParsingFailedException("Invalid statement: ${ctx.text}", ctx)
    }

    private Statement ifElseStatement(IfElseStatementContext ctx) {
        final expression = ast( parExpression(ctx.parExpression()), ctx.parExpression() )
        final condition = ast( boolX(expression), expression )
        final thenStmt = statementOrBlock(ctx.tb)
        final elseStmt = ctx.ELSE()
            ? statementOrBlock(ctx.fb)
            : EmptyStatement.INSTANCE
        ifElseS(condition, thenStmt, elseStmt)
    }

    private Statement statementOrBlock(StatementOrBlockContext ctx) {
        return ctx.statement()
            ? statement(ctx.statement())
            : blockStatements(ctx.blockStatements())
    }

    private BlockStatement blockStatements(BlockStatementsContext ctx) {
        if( !ctx )
            return block(new VariableScope(), List<Statement>.of())
        final statements = ctx.statement().collect( this.&statement )
        ast( block(new VariableScope(), statements), ctx )
    }

    private Statement tryCatchStatement(TryCatchStatementContext ctx) {
        final tryStatement = statementOrBlock(ctx.statementOrBlock())
        final catchClauses = ctx.catchClause().collect( this.&catchClause )
        final result = tryCatchS(tryStatement)
        for( final clause : catchClauses )
            for( final stmt : clause )
                result.addCatch(stmt)
        return result
    }

    private List<CatchStatement> catchClause(CatchClauseContext ctx) {
        final types = catchTypes(ctx.catchTypes())
        return types.collect { type ->
            final name = identifier(ctx.identifier())
            final variable = ast( param(type, name), ctx.identifier() )
            final code = statementOrBlock(ctx.statementOrBlock())
            ast( new CatchStatement(variable, code), ctx )
        }
    }

    private List<ClassNode> catchTypes(CatchTypesContext ctx) {
        if( !ctx )
            return Collections.singletonList( ClassHelper.dynamicType() )

        return ctx.qualifiedClassName().collect( this.&qualifiedClassName )
    }

    private Statement returnStatement(ExpressionContext ctx) {
        final result = ctx
            ? expression(ctx)
            : ConstantExpression.EMPTY_EXPRESSION
        returnS(result)
    }

    private Statement throwStatement(ExpressionContext ctx) {
        final result = ctx
            ? expression(ctx)
            : ConstantExpression.EMPTY_EXPRESSION
        throwS(result)
    }

    private Statement assertStatement(AssertStatementContext ctx) {
        final condition = ast( boolX(expression(ctx.condition)), ctx.condition )
        ctx.message
            ? new AssertStatement(condition, expression(ctx.message))
            : new AssertStatement(condition)
    }

    private Statement variableDeclaration(VariableDeclarationContext ctx) {
        if( ctx.typeNamePairs() ) {
            // multiple assignment
            final variables = ctx.typeNamePairs().typeNamePair().collect { pair ->
                final name = identifier(pair.identifier())
                final type = type(pair.type())
                ast( varX(name, type), pair.identifier() )
            }
            final target = variables.size() > 1
                ? new ArgumentListExpression(variables as List<Expression>)
                : variables.first()
            final initializer = expression(ctx.initializer)
            return stmt(ast( declX(target, initializer), ctx ))
        }
        else {
            // single assignment
            final type = type(ctx.type())
            final decl = ctx.variableDeclarator()
            final name = identifier(decl.identifier())
            final target = ast( varX(name, type), decl.identifier() )
            final initializer = decl.initializer
                ? expression(decl.initializer)
                : EmptyExpression.INSTANCE
            return stmt(ast( declX(target, initializer), ctx ))
        }
    }

    private Statement assignment(MultipleAssignmentStatementContext ctx) {
        final left = variableNames(ctx.variableNames())
        final right = expression(ctx.right)
        return stmt(ast( assignX(left, right), ctx ))
    }

    private Expression variableNames(VariableNamesContext ctx) {
        final vars = ctx.identifier().collect( this.&variableName )
        return ast( new TupleExpression(vars), ctx )
    }

    private Expression variableName(IdentifierContext ctx) {
        return ast( varX(identifier(ctx)), ctx )
    }

    private Statement assignment(AssignmentStatementContext ctx) {
        final left = expression(ctx.left)
        if( left instanceof VariableExpression && isInsideParentheses(left) ) {
            if( left.<Number>getNodeMetaData(INSIDE_PARENTHESES_LEVEL).intValue() > 1 )
                throw createParsingFailedException("Nested parenthesis is not allowed in multiple assignment, e.g. ((a)) = b", ctx)

            final tuple = ast( new TupleExpression(left), ctx.left )
            return stmt(ast( binX(tuple, token(ctx.op), expression(ctx.right)), ctx ))
        }

        if ( isAssignmentLhsValid(left) )
            return stmt(ast( binX(left, token(ctx.op), expression(ctx.right)), ctx ))

        throw createParsingFailedException("The left-hand side of an assignment should be a variable or a property expression", ctx)
    }

    private boolean isAssignmentLhsValid(Expression left) {
        // e.g. p = 123
        if( left instanceof VariableExpression && !isInsideParentheses(left) )
            return true
        // e.g. obj.p = 123
        if( left instanceof PropertyExpression )
            return true
        // e.g. map[a] = 123 OR map['a'] = 123 OR map["$a"] = 123
        if( left instanceof BinaryExpression && left.operation.type == Types.LEFT_SQUARE_BRACKET )
            return true
        return false
    }

    private Statement expressionStatement(ExpressionStatementContext ctx) {
        final base = expression(ctx.expression())
        final expression = ctx.argumentList()
            ? methodCall(base, argumentList(ctx.argumentList()))
            : base
        return ast( stmt(expression), ctx )
    }

    /// GROOVY EXPRESSIONS

    private Expression expression(ExpressionContext ctx) {
        if( ctx instanceof AddSubExprAltContext )
            return ast( binary(ctx.left, ctx.op, ctx.right), ctx )

        if( ctx instanceof BitwiseAndExprAltContext )
            return ast( binary(ctx.left, ctx.op, ctx.right), ctx )

        if( ctx instanceof BitwiseOrExprAltContext )
            return ast( binary(ctx.left, ctx.op, ctx.right), ctx )

        if( ctx instanceof ConditionalExprAltContext )
            return ast( ternary(ctx), ctx )

        if( ctx instanceof EqualityExprAltContext )
            return ast( binary(ctx.left, ctx.op, ctx.right), ctx )

        if( ctx instanceof ExclusiveOrExprAltContext )
            return ast( binary(ctx.left, ctx.op, ctx.right), ctx )

        if( ctx instanceof LogicalAndExprAltContext )
            return ast( binary(ctx.left, ctx.op, ctx.right), ctx )

        if( ctx instanceof LogicalOrExprAltContext )
            return ast( binary(ctx.left, ctx.op, ctx.right), ctx )

        if( ctx instanceof MultDivExprAltContext )
            return ast( binary(ctx.left, ctx.op, ctx.right), ctx )

        if( ctx instanceof PathExprAltContext )
            return ast( pathExpression(ctx), ctx )

        if( ctx instanceof PowerExprAltContext )
            return ast( binary(ctx.left, ctx.op, ctx.right), ctx )

        if( ctx instanceof RegexExprAltContext )
            return ast( binary(ctx.left, ctx.op, ctx.right), ctx )

        if( ctx instanceof RelationalExprAltContext )
            return ast( binary(ctx.left, ctx.op, ctx.right), ctx )

        if( ctx instanceof RelationalCastExprAltContext ) {
            final operand = expression(ctx.expression())
            final type = type(ctx.type())
            return ast( asX(type, operand), ctx )
        }

        if( ctx instanceof RelationalTypeExprAltContext ) {
            final right = ast( new ClassExpression(type(ctx.type(), false)), ctx.type() )
            return ast( binary(ctx.left, ctx.op, right), ctx )
        }

        if( ctx instanceof ShiftExprAltContext )
            return ast( shift(ctx), ctx )

        if( ctx instanceof UnaryAddExprAltContext )
            return ast( unaryAdd(expression(ctx.expression()), ctx.op), ctx )

        if( ctx instanceof UnaryNotExprAltContext )
            return ast( unaryNot(expression(ctx.expression()), ctx.op), ctx )

        if( ctx instanceof IncompleteExprAltContext )
            return incompleteExpression(ctx.incompleteExpression())

        throw createParsingFailedException("Invalid expression: ${ctx.text}", ctx)
    }

    private Expression binary(ExpressionContext left, ParserToken op, ExpressionContext right) {
        binX(expression(left), token(op), expression(right))
    }

    private Expression binary(ExpressionContext left, ParserToken op, Expression right) {
        binX(expression(left), token(op), right)
    }

    private Expression shift(ShiftExprAltContext ctx) {
        final left = expression(ctx.left)
        final right = expression(ctx.right)

        if( ctx.riOp )
            return new RangeExpression(left, right, true)
        if( ctx.reOp )
            return new RangeExpression(left, right, false, true)

        def op
        if( ctx.dlOp )
            op = token(ctx.dlOp, 2)
        if( ctx.dgOp )
            op = token(ctx.dgOp, 2)
        if( ctx.tgOp )
            op = token(ctx.tgOp, 3)

        return binX(left, op, right)
    }

    private Expression ternary(ConditionalExprAltContext ctx) {
        if( ctx.ELVIS() )
            return elvisX(expression(ctx.condition), expression(ctx.fb))

        final condition = ast( boolX(expression(ctx.condition)), ctx.condition )
        return ternaryX(condition, expression(ctx.tb), expression(ctx.fb))
    }

    private Expression unaryAdd(Expression expression, ParserToken op) {
        if( op.type == ScriptParser.ADD )
            return new UnaryPlusExpression(expression)

        if( op.type == ScriptParser.SUB )
            return new UnaryMinusExpression(expression)

        throw new IllegalStateException()
    }

    private Expression unaryNot(Expression expression, ParserToken op) {
        if( op.type == ScriptParser.NOT )
            return new NotExpression(expression)

        if( op.type == ScriptParser.BITNOT )
            return new BitwiseNegationExpression(expression)

        throw new IllegalStateException()
    }

    /// -- PATH EXPRESSIONS

    private Expression pathExpression(PathExprAltContext ctx) {
        try {
            final primary = primary(ctx.primary())
            return ctx.pathElement().inject(primary, (acc, el) -> pathElement(acc, el))
        }
        catch( IllegalStateException e ) {
            throw createParsingFailedException("Invalid expression: ${ctx.text}", ctx)
        }
    }

    private Expression pathElement(Expression expression, PathElementContext ctx) {
        if( ctx instanceof PropertyPathExprAltContext )
            return ast( pathPropertyElement(expression, ctx), expression, ctx )

        if( ctx instanceof ClosurePathExprAltContext ) {
            final closure = closure(ctx.closure())
            return ast( pathClosureElement(expression, closure), expression, ctx )
        }

        if( ctx instanceof ClosureWithLabelsPathExprAltContext ) {
            final closure = closureWithLabels(ctx.closureWithLabels())
            return ast( pathClosureElement(expression, closure), expression, ctx )
        }

        if( ctx instanceof ArgumentsPathExprAltContext )
            return ast( pathArgumentsElement(expression, ctx.arguments()), expression, ctx )

        if( ctx instanceof IndexPathExprAltContext )
            return ast( pathIndexElement(expression, ctx.indexPropertyArgs()), expression, ctx )

        throw new IllegalStateException()
    }

    private Expression pathPropertyElement(Expression expression, PropertyPathExprAltContext ctx) {
        final property = ast( constX(namePart(ctx.namePart())), ctx.namePart() )
        final safe = ctx.SAFE_DOT() != null || ctx.SPREAD_DOT() != null
        final result = new PropertyExpression(expression, property, safe)
        if( ctx.SPREAD_DOT() )
            result.setSpreadSafe(true)
        return result
    }

    private String namePart(NamePartContext ctx) {
        if( ctx.keywords() )
            return keywords(ctx.keywords())

        if( ctx.identifier() )
            return identifier(ctx.identifier())

        if( ctx.stringLiteral() )
            return stringLiteral(ctx.stringLiteral())

        throw new IllegalStateException()
    }

    private Expression pathClosureElement(Expression expression, Expression closure) {
        if( expression instanceof MethodCallExpression ) {
            // append closure to method call arguments
            final call = (MethodCallExpression)expression

            // normal arguments, e.g. 1, 2
            if ( call.arguments !instanceof ArgumentListExpression )
                throw new IllegalStateException()

            final arguments = (ArgumentListExpression)call.arguments
            arguments.addExpression(closure)
            return call

            // TODO: only needed if namedArgs uses TupleExpression
            // named arguments, e.g. x: 1, y: 2
            // if ( arguments instanceof TupleExpression ) {
            //     final tuple = (TupleExpression) arguments
            //     if( !tuple.expressions )
            //         throw new IllegalStateException()
            //     final namedArguments = (NamedArgumentListExpression) tuple.getExpression(0)
            //     call.arguments = args( mapX(namedArguments.mapEntryExpressions), closure )
            //     return call
            // }
        }

        final arguments = ast( args(closure), closure )

        // e.g. obj.m { }
        if( expression instanceof PropertyExpression )
            return propMethodCall(expression, arguments)

        // e.g. m { }, "$m" { }, "m" { }
        if( expression instanceof VariableExpression || expression instanceof GStringExpression || (expression instanceof ConstantExpression && expression.value instanceof String) )
            return thisMethodCall(expression, arguments)

        // e.g. <expr> { } -> <expr>.call { }
        return callMethodCall(expression, arguments)
    }

    private Expression pathArgumentsElement(Expression caller, ArgumentsContext ctx) {
        final arguments = argumentList(ctx.argumentList())
        return ast( methodCall(caller, arguments), caller, ctx )
    }

    private Expression pathIndexElement(Expression expression, IndexPropertyArgsContext ctx) {
        final elements = expressionList(ctx.expressionList())

        Expression index
        if( elements.size() > 1 ) {
            // e.g. a[1, 2]
            index = listX(elements)
            index.setWrapped(true)
        }
        else if( elements.first() instanceof SpreadExpression ) {
            // e.g. a[*[1, 2]]
            index = listX(elements)
            index.setWrapped(false)
        }
        else {
            // e.g. a[1]
            index = elements.first()
        }

        return indexX(expression, ast(index, ctx))
    }

    /// -- PRIMARY EXPRESSIONS

    private Expression primary(PrimaryContext ctx) {
        if( ctx instanceof IdentifierPrmrAltContext )
            return ast( varX(identifier(ctx.identifier())), ctx )

        if( ctx instanceof LiteralPrmrAltContext )
            return ast( literal(ctx.literal()), ctx )

        if( ctx instanceof GstringPrmrAltContext )
            return ast( gstring(ctx.gstring()), ctx )

        if( ctx instanceof NewPrmrAltContext )
            return ast( creator(ctx.creator()), ctx )

        if( ctx instanceof ParenPrmrAltContext )
            return ast( parExpression(ctx.parExpression()), ctx )

        if( ctx instanceof ClosurePrmrAltContext )
            return ast( closure(ctx.closure()), ctx )

        if( ctx instanceof ListPrmrAltContext )
            return ast( list(ctx.list()), ctx )

        if( ctx instanceof MapPrmrAltContext )
            return ast( map(ctx.map()), ctx )

        if( ctx instanceof BuiltInTypePrmrAltContext )
            return ast( builtInType(ctx.builtInType()), ctx )

        throw createParsingFailedException("Invalid expression: ${ctx.text}", ctx)
    }

    private Expression builtInType(BuiltInTypeContext ctx) {
        varX(ctx.text)
    }

    private String identifier(IdentifierContext ctx) {
        ctx.text
    }

    private String keywords(KeywordsContext ctx) {
        ctx.text
    }

    private Expression literal(LiteralContext ctx) {
        if( ctx instanceof IntegerLiteralAltContext )
            return ast( integerLiteral(ctx), ctx )

        if( ctx instanceof FloatingPointLiteralAltContext )
            return ast( floatingPointLiteral(ctx), ctx )

        if( ctx instanceof StringLiteralAltContext )
            return ast( string(ctx.stringLiteral()), ctx )

        if( ctx instanceof BooleanLiteralAltContext )
            return ast( constX(ctx.text == 'true'), ctx )

        if( ctx instanceof NullLiteralAltContext )
            return ast( constX(null), ctx )

        throw createParsingFailedException("Invalid expression: ${ctx.text}", ctx)
    }

    private Expression integerLiteral(IntegerLiteralAltContext ctx) {
        Number num
        try {
            num = Numbers.parseInteger(ctx.text)
        }
        catch( Exception e ) {
            numberFormatError = new Tuple2(ctx, e)
        }

        constX(num, true)
    }

    private Expression floatingPointLiteral(FloatingPointLiteralAltContext ctx) {
        Number num
        try {
            num = Numbers.parseDecimal(ctx.text)
        }
        catch( Exception e ) {
            numberFormatError = new Tuple2(ctx, e)
        }

        constX(num, true)
    }

    private Expression string(StringLiteralContext ctx) {
        final text = ctx.text
        final result = constX(stringLiteral(text))
        if( text.startsWith(SQ_STR)    ) result.putNodeMetaData(QUOTE_CHAR, SQ_STR)
        if( text.startsWith(DQ_STR)    ) result.putNodeMetaData(QUOTE_CHAR, DQ_STR)
        if( text.startsWith(TSQ_STR)   ) result.putNodeMetaData(QUOTE_CHAR, TSQ_STR)
        if( text.startsWith(TDQ_STR)   ) result.putNodeMetaData(QUOTE_CHAR, TDQ_STR)
        if( text.startsWith(SLASH_STR) ) result.putNodeMetaData(QUOTE_CHAR, SLASH_STR)
        return result
    }

    private String stringLiteral(StringLiteralContext ctx) {
        stringLiteral(ctx.text)
    }

    private String stringLiteral(String text) {
        final startsWithSlash = text.startsWith(SLASH_STR)

        if( text.startsWith(TSQ_STR) || text.startsWith(TDQ_STR) ) {
            text = StringUtils.removeCR(text)
            text = StringUtils.trimQuotations(text, 3)
        }
        else if( text.startsWith(SQ_STR) || text.startsWith(DQ_STR) || startsWithSlash ) {
            // the slashy string can span rows, so we have to remove CR for it
            if( startsWithSlash )
                text = StringUtils.removeCR(text)
            text = StringUtils.trimQuotations(text, 1)
        }

        final slashyType = startsWithSlash
            ? StringUtils.SLASHY
            : StringUtils.NONE_SLASHY

        return StringUtils.replaceEscapes(text, slashyType)
    }

    private Expression gstring(GstringContext ctx) {
        final text = ctx.text
        final verbatimText = stringLiteral(text)
        final List<ConstantExpression> strings = []
        final List<Expression> values = []

        for( final part : ctx.gstringDqPart() ) {
            if( part instanceof GstringDqTextAltContext )
                strings << ast( constX(part.text), part )

            if( part instanceof GstringDqPathAltContext )
                values << ast( gstringPath(part), part )

            if( part instanceof GstringDqExprAltContext )
                values << expression(part.expression())
        }

        for( final part : ctx.gstringTdqPart() ) {
            if( part instanceof GstringTdqTextAltContext )
                strings << ast( constX(part.text), part )

            if( part instanceof GstringTdqPathAltContext )
                values << ast( gstringPath(part), part )

            if( part instanceof GstringTdqExprAltContext )
                values << expression(part.expression())
        }

        final result = new GStringExpression(verbatimText, strings, values)
        if( text.startsWith(DQ_STR)  ) result.putNodeMetaData(QUOTE_CHAR, DQ_STR)
        if( text.startsWith(TDQ_STR) ) result.putNodeMetaData(QUOTE_CHAR, TDQ_STR)
        return result
    }

    private Expression gstringPath(ParserRuleContext ctx) {
        final names = ctx.text.tokenize('.')
        int currentLine = ctx.getStart().getLine()
        int currentChar = ctx.getStart().getCharPositionInLine() + 1
        final varName = names.head().substring(1)
        Expression result = varX(varName)
        currentChar += 1
        result.setLineNumber(currentLine)
        result.setColumnNumber(currentChar)
        currentChar += varName.size()
        result.setLastLineNumber(currentLine)
        result.setLastColumnNumber(currentChar)

        for( final propName : names.tail() ) {
            final property = constX(propName)
            currentChar += 1
            property.setLineNumber(currentLine)
            property.setColumnNumber(currentChar)
            currentChar += propName.size()
            property.setLastLineNumber(currentLine)
            property.setLastColumnNumber(currentChar)
            result = ast( propX(result, property), result, property )
        }

        return result
    }

    private Expression creator(CreatorContext ctx) {
        final type = createdName(ctx.createdName())
        final arguments = argumentList(ctx.arguments().argumentList())
        ctorX(type, arguments)
    }

    private Expression parExpression(ParExpressionContext ctx) {
        final expression = expression(ctx.expression())
        expression.getNodeMetaData(INSIDE_PARENTHESES_LEVEL, k -> new AtomicInteger()).getAndAdd(1)
        return expression
    }

    private Expression closure(ClosureContext ctx) {
        final params = parameters(ctx.formalParameterList())
        final code = blockStatements(ctx.blockStatements())
        ast( closureX(params, code), ctx )
    }

    private Expression closureWithLabels(ClosureWithLabelsContext ctx) {
        final params = parameters(ctx.formalParameterList())
        final code = blockStatementsWithLabels(ctx.blockStatementsWithLabels())
        ast( closureX(params, code), ctx )
    }

    private BlockStatement blockStatementsWithLabels(BlockStatementsWithLabelsContext ctx) {
        final statements = ctx.statementOrLabeled().collect( this.&statementOrLabeled )
        ast( block(new VariableScope(), statements), ctx )
    }

    private Statement statementOrLabeled(StatementOrLabeledContext ctx) {
        final result = statement(ctx.statement())
        if( ctx.identifier() ) {
            final label = identifier(ctx.identifier())
            result.addStatementLabel(label)
        }
        return result
    }

    private Expression list(ListContext ctx) {
        if( ctx.COMMA() && !ctx.expressionList() )
            throw createParsingFailedException("Empty list literal should not contain any comma(,)", ctx.COMMA())

        listX(expressionList(ctx.expressionList()))
    }

    private List<Expression> expressionList(ExpressionListContext ctx) {
        if( !ctx )
            return Collections.emptyList()
        
        ctx.expressionListElement().collect( this.&listElement )
    }

    private Expression listElement(ExpressionListElementContext ctx) {
        final element = expression(ctx.expression())
        ctx.MUL()
            ? ast( new SpreadExpression(element), ctx )
            : ast( element, ctx )
    }

    private Expression map(MapContext ctx) {
        if( !ctx.mapEntryList() )
            return new MapExpression()

        final entries = ctx.mapEntryList().mapEntry().collect( this.&mapEntry )
        mapX(entries)
    }

    private MapEntryExpression mapEntry(MapEntryContext ctx) {
        final value = expression(ctx.expression())
        final key = ctx.MUL()
            ? ast( new SpreadMapExpression(value), ctx )
            : mapEntryLabel(ctx.mapEntryLabel())
        ast( entryX(key, value), ctx )
    }

    private Expression mapEntryLabel(MapEntryLabelContext ctx) {
        if( ctx.keywords() )
            return ast( constX(keywords(ctx.keywords())), ctx )

        if( ctx.primary() ) {
            final expression = primary(ctx.primary())
            return expression instanceof VariableExpression && !isInsideParentheses(expression)
                ? ast( constX(((VariableExpression)expression).name), expression )
                : ast( expression, ctx )
        }

        throw createParsingFailedException("Unsupported map entry label: ${ctx.text}", ctx)
    }

    private Expression methodCall(Expression caller, Expression arguments) {
        // e.g. (obj.x)(), (obj.@x)()
        if( isInsideParentheses(caller) )
            return callMethodCall(caller, arguments)

        // e.g. obj.a(1, 2)
        if( caller instanceof PropertyExpression )
            return propMethodCall(caller, arguments)

        // e.g. m(), "$m"(), "m"()
        if( caller instanceof VariableExpression || caller instanceof GStringExpression || (caller instanceof ConstantExpression && caller.value instanceof String) )
            return thisMethodCall(caller, arguments)

        // e.g. <expr>(<args>) -> <expr>.call(<args>)
        return callMethodCall(caller, arguments)
    }

    private Expression propMethodCall(PropertyExpression caller, Expression arguments) {
        final result = callX(caller.objectExpression, caller.property, arguments)
        result.setImplicitThis(false)
        result.setSafe(caller.isSafe())
        result.setSpreadSafe(caller.isSpreadSafe())

        // method call obj*.m() -> safe=false and spreadSafe=true
        // property access obj*.p -> safe=true and spreadSafe=true
        if( caller.isSpreadSafe() )
            result.setSafe(false)

        return ast( result, caller, arguments )
    }

    private Expression thisMethodCall(Expression caller, Expression arguments) {
        final object = varX('this')
        object.setColumnNumber(caller.getColumnNumber())
        object.setLineNumber(caller.getLineNumber())

        final name = caller instanceof VariableExpression
            ? ast( constX(caller.text), caller )
            : caller

        return ast( callX(object, name, arguments), caller, arguments )
    }

    private Expression callMethodCall(Expression caller, Expression arguments) {
        final call = callX(caller, CALL_STR, arguments)
        call.setImplicitThis(false)
        return ast( call, caller, arguments )
    }

    private Expression argumentList(ArgumentListContext ctx) {
        if( !ctx )
            return new ArgumentListExpression()

        final List<Expression> arguments = []
        final List<MapEntryExpression> opts = []

        for( final ctx1 : ctx.argumentListElement() ) {
            if( ctx1.expressionListElement() )
                arguments << listElement(ctx1.expressionListElement())

            else if( ctx1.namedArg() )
                opts << namedArg(ctx1.namedArg())

            else
                throw createParsingFailedException("Invalid method argument: ${ctx.text}", ctx)
        }

        // TODO: validate duplicate named arguments ?
        // TODO: only named arguments -> TupleExpression ?
        if( opts )
            arguments.push( mapX(opts) )

        final result = ast( args(arguments), ctx )
        if( opts )
            result.putNodeMetaData(HAS_NAMED_ARGS, true)
        return result
    }

    private MapEntryExpression namedArg(NamedArgContext ctx) {
        final value = expression(ctx.expression())
        final key = ctx.MUL()
            ? new SpreadMapExpression(value)
            : ast( namedArgLabel(ctx.namedArgLabel()), ctx.namedArgLabel() )
        ast( new MapEntryExpression(key, value), ctx )
    }

    private Expression namedArgLabel(NamedArgLabelContext ctx) {
        if( ctx.keywords() )
            return constX(keywords(ctx.keywords()))

        if( ctx.identifier() )
            return constX(identifier(ctx.identifier()))

        if( ctx.literal() )
            return literal(ctx.literal())

        if( ctx.gstring() )
            return gstring(ctx.gstring())

        throw createParsingFailedException("Invalid method named argument: ${ctx.text}", ctx)
    }

    private Expression incompleteExpression(IncompleteExpressionContext ctx) {
        final head = ctx.identifier().head()
        final object = ast( varX(identifier(head)), head )
        final propX = ctx.identifier().tail().inject(object) { acc, ident ->
            final name = ast( constX(identifier(ident)), ident )
            ast( new PropertyExpression(acc, name), acc, name )
        }
        final result = ast( new PropertyExpression(propX, ''), ctx )
        collectSyntaxError(new SyntaxException("Incomplete expression", result))
        return result
    }

    /// MISCELLANEOUS

    private Parameter[] parameters(FormalParameterListContext ctx) {
        // NOTE: implicit `it` is not allowed
        if( !ctx )
            return null

        for( int i = 0, n = ctx.formalParameter().size(); i < n - 1; i += 1 ) {
            final ctx1 = ctx.formalParameter(i)
            if( ctx1.ELLIPSIS() )
                throw createParsingFailedException("The var-arg parameter must be the last parameter", ctx1)
        }

        final params = ctx.formalParameter().collect( this.&parameter )
        for( int n = params.size(), i = n - 1; i >= 0; i -= 1 ) {
            final param = params[i]
            for( final other : params ) {
                if( other == param )
                    continue
                if( other.name == param.name )
                    throw createParsingFailedException("Duplicated parameter '${param.name}' found", param)
            }
        }

        return params as Parameter[]
    }

    private Parameter parameter(FormalParameterContext ctx) {
        ClassNode type = type(ctx.type())
        if( ctx.ELLIPSIS() ) {
            type = type.makeArray()
            type = ctx.type()
                ? ast( type, ctx.type(), ast(constX('...'), ctx.ELLIPSIS()) )
                : ast( type, ctx.ELLIPSIS() )
        }

        final name = identifier(ctx.identifier())
        final defaultValue = ctx.expression()
            ? expression(ctx.expression())
            : null
        ast( param(type, name, defaultValue), ctx )
    }

    private Token token(ParserToken token, int cardinality = 1) {
        final text = cardinality == 1
            ? token.text
            : token.text * cardinality
        final type = token.type == RANGE_EXCLUSIVE_RIGHT || token.type == RANGE_INCLUSIVE
            ? Types.RANGE_OPERATOR
            : Types.lookup(text, Types.ANY)
        new Token( type, text, token.getLine(), token.getCharPositionInLine() + 1 )
    }

    private ClassNode createdName(CreatedNameContext ctx) {
        if( ctx.qualifiedClassName() ) {
            final classNode = qualifiedClassName(ctx.qualifiedClassName())
            if( ctx.typeArguments() )
                classNode.setGenericsTypes( typeArguments(ctx.typeArguments()) )
            return classNode
        }

        if( ctx.primitiveType() )
            return primitiveType(ctx.primitiveType())

        throw createParsingFailedException("Unrecognized created name: ${ctx.text}", ctx)
    }

    private ClassNode primitiveType(PrimitiveTypeContext ctx) {
        final classNode = ClassHelper.make(ctx.text).getPlainNodeReference(false)
        return ast( classNode, ctx )
    }

    private ClassNode qualifiedClassName(QualifiedClassNameContext ctx, boolean allowProxy=true) {
        final text = ctx.text
        final classNode = ClassHelper.make(text)
        if( text.contains('.') )
            classNode.putNodeMetaData(IS_FULLY_QUALIFIED, true)

        if( classNode.isUsingGenerics() && allowProxy ) {
            final proxy = ClassHelper.makeWithoutCaching(classNode.name)
            proxy.setRedirect(classNode)
            return proxy
        }

        return ast( classNode, ctx )
    }

    private ClassNode type(TypeContext ctx, boolean allowProxy=true) {
        if( !ctx )
            return ClassHelper.dynamicType()

        if( ctx.qualifiedClassName() ) {
            final classNode = qualifiedClassName(ctx.qualifiedClassName(), allowProxy)
            if( ctx.typeArguments() )
                classNode.setGenericsTypes( typeArguments(ctx.typeArguments()) )
            return classNode
        }

        if( ctx.primitiveType() )
            return primitiveType(ctx.primitiveType())

        throw createParsingFailedException("Unrecognized type: ${ctx.text}", ctx)
    }

    private GenericsType[] typeArguments(TypeArgumentsContext ctx) {
        ctx.type().collect( this.&genericsType ) as GenericsType[]
    }

    private GenericsType genericsType(TypeContext ctx) {
        ast( new GenericsType(type(ctx)), ctx )
    }

    /// HELPERS

    private boolean isInsideParentheses(NodeMetaDataHandler nodeMetaDataHandler) {
        Number insideParenLevel = nodeMetaDataHandler.getNodeMetaData(INSIDE_PARENTHESES_LEVEL)
        return insideParenLevel != null && insideParenLevel.intValue() > 0
    }

    private CompilationFailedException createParsingFailedException(String msg, ParserRuleContext ctx) {
        return createParsingFailedException(
            new SyntaxException(msg,
                ctx.start.getLine(),
                ctx.start.getCharPositionInLine() + 1,
                ctx.stop.getLine(),
                ctx.stop.getCharPositionInLine() + 1 + ctx.stop.getText().length()))
    }

    private CompilationFailedException createParsingFailedException(String msg, Tuple2<Integer,Integer> start, Tuple2<Integer,Integer> end) {
        return createParsingFailedException(
            new SyntaxException(msg,
                start.getV1(),
                start.getV2(),
                end.getV1(),
                end.getV2()))
    }

    private CompilationFailedException createParsingFailedException(String msg, ASTNode node) {
        return createParsingFailedException(
            new SyntaxException(msg,
                node.getLineNumber(),
                node.getColumnNumber(),
                node.getLastLineNumber(),
                node.getLastColumnNumber()))
    }

    private CompilationFailedException createParsingFailedException(String msg, TerminalNode node) {
        return createParsingFailedException(msg, node.getSymbol())
    }

    private CompilationFailedException createParsingFailedException(String msg, ParserToken token) {
        return createParsingFailedException(
            new SyntaxException(msg,
                token.getLine(),
                token.getCharPositionInLine() + 1,
                token.getLine(),
                token.getCharPositionInLine() + 1 + token.getText().length()))
    }

    private CompilationFailedException createParsingFailedException(Throwable t) {
        if( t instanceof SyntaxException )
            collectSyntaxError(t)

        else if( t instanceof GroovySyntaxError )
            collectSyntaxError(
                    new SyntaxException(
                            t.getMessage(),
                            t,
                            t.getLine(),
                            t.getColumn()))

        else if( t instanceof Exception )
            collectException(t)

        return new CompilationFailedException(
                CompilePhase.PARSING.getPhaseNumber(),
                sourceUnit,
                t)
    }

    private void collectSyntaxError(SyntaxException e) {
        sourceUnit.getErrorCollector().addFatalError(new SyntaxErrorMessage(e, sourceUnit))
    }

    private void collectException(Exception e) {
        sourceUnit.getErrorCollector().addException(e, sourceUnit)
    }

    private void removeErrorListeners() {
        lexer.removeErrorListeners()
        parser.removeErrorListeners()
    }

    private void addErrorListeners() {
        lexer.addErrorListener(createANTLRErrorListener())
        parser.addErrorListener(createANTLRErrorListener())
    }

    private ANTLRErrorListener createANTLRErrorListener() {
        return new ANTLRErrorListener() {
            @Override
            void syntaxError(Recognizer recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
                collectSyntaxError(new SyntaxException(msg, line, charPositionInLine + 1))
            }

            @Override
            void reportAmbiguity(Parser recognizer, DFA dfa, int startIndex, int stopIndex, boolean exact, BitSet ambigAlts, ATNConfigSet configs) {}

            @Override
            void reportAttemptingFullContext(Parser recognizer, DFA dfa, int startIndex, int stopIndex, BitSet conflictingAlts, ATNConfigSet configs) {}

            @Override
            void reportContextSensitivity(Parser recognizer, DFA dfa, int startIndex, int stopIndex, int prediction, ATNConfigSet configs) {}
        }
    }

    private static final String CALL_STR = 'call'
    private static final String SLASH_STR = '/'
    private static final String TDQ_STR = '"""'
    private static final String TSQ_STR = "'''"
    private static final String SQ_STR = "'"
    private static final String DQ_STR = '"'

    private static final String HAS_NAMED_ARGS = "_HAS_NAMED_ARSG"
    private static final String INSIDE_PARENTHESES_LEVEL = "_INSIDE_PARENTHESES_LEVEL"
    private static final String IS_FULLY_QUALIFIED = "_IS_FULLY_QUALIFIED"
    private static final String QUOTE_CHAR = "_QUOTE_CHAR"

}
