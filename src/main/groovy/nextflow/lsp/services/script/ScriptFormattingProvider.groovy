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
import nextflow.lsp.services.util.CustomFormattingOptions
import nextflow.lsp.services.util.Formatter
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
import nextflow.script.v2.ScriptVisitorSupport
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

import static nextflow.script.v2.ASTHelpers.*

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

        if( !ast.hasAST(uri) || ast.hasErrors(uri) )
            return Collections.emptyList()

        final sourceUnit = ast.getSourceUnit(uri)
        final oldText = sourceUnit.getSource().getReader().getText()
        final range = new Range(new Position(0, 0), Positions.getPosition(oldText, oldText.size()))
        final visitor = new FormattingVisitor(sourceUnit, options, ast)
        visitor.visit()
        final newText = visitor.toString()

        return List.of( new TextEdit(range, newText) )
    }

}

@CompileStatic
class FormattingVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit

    private CustomFormattingOptions options

    private ASTNodeCache ast

    private Formatter fmt

    private int maxIncludeWidth = 0

    FormattingVisitor(SourceUnit sourceUnit, CustomFormattingOptions options, ASTNodeCache ast) {
        this.sourceUnit = sourceUnit
        this.options = options
        this.ast = ast
        this.fmt = new Formatter(options)
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

    String toString() {
        return fmt.toString()
    }

    // script statements

    @Override
    void visitFeatureFlag(FeatureFlagNode node) {
        fmt.appendComments(node)
        fmt.append(node.name)
        fmt.append(' = ')
        fmt.visit(node.value)
        fmt.appendNewLine()
    }

    @Override
    void visitInclude(IncludeNode node) {
        fmt.appendComments(node)
        for( final module : node.modules ) {
            fmt.append('include { ')
            fmt.append(module.@name)
            if( module.alias ) {
                fmt.append(' as ')
                fmt.append(module.alias)
            }
            if( options.harshilAlignment() ) {
                final padding = maxIncludeWidth - getIncludeWidth(module)
                fmt.append(' ' * padding)
            }
            fmt.append(' } from ')
            fmt.visit(node.source)
            fmt.appendNewLine()
        }
    }

    @Override
    void visitFunction(FunctionNode node) {
        fmt.appendComments(node)
        fmt.append('def ')
        fmt.append(node.name)
        fmt.append('(')
        fmt.visitParameters(node.parameters)
        fmt.append(') {\n')
        fmt.incIndent()
        fmt.visit(node.code)
        fmt.decIndent()
        fmt.append('}\n')
    }

    @Override
    void visitProcess(ProcessNode node) {
        fmt.appendComments(node)
        fmt.append('process ')
        fmt.append(node.name)
        fmt.append(' {\n')
        fmt.incIndent()
        if( node.directives instanceof BlockStatement ) {
            visitDirectives(node.directives)
            fmt.appendNewLine()
        }
        if( node.inputs instanceof BlockStatement ) {
            fmt.appendIndent()
            fmt.append('input:\n')
            visitDirectives(node.inputs)
            fmt.appendNewLine()
        }
        if( node.outputs instanceof BlockStatement ) {
            fmt.appendIndent()
            fmt.append('output:\n')
            visitDirectives(node.outputs)
            fmt.appendNewLine()
        }
        if( node.when !instanceof EmptyExpression ) {
            fmt.appendIndent()
            fmt.append('when:\n')
            fmt.appendIndent()
            fmt.visit(node.when)
            fmt.append('\n\n')
        }
        fmt.appendIndent()
        fmt.append(node.type)
        fmt.append(':\n')
        fmt.visit(node.exec)
        if( node.stub !instanceof EmptyStatement ) {
            fmt.appendNewLine()
            fmt.appendIndent()
            fmt.append('stub:\n')
            fmt.visit(node.stub)
        }
        fmt.decIndent()
        fmt.append('}\n')
    }

    @Override
    void visitWorkflow(WorkflowNode node) {
        fmt.appendComments(node)
        fmt.append('workflow')
        if( node.name ) {
            fmt.append(' ')
            fmt.append(node.name)
        }
        fmt.append(' {\n')
        fmt.incIndent()
        if( node.takes instanceof BlockStatement ) {
            fmt.appendIndent()
            fmt.append('take:\n')
            fmt.visit(node.takes)
            fmt.appendNewLine()
        }
        if( node.main instanceof BlockStatement ) {
            if( node.takes instanceof BlockStatement || node.emits instanceof BlockStatement || node.publishers instanceof BlockStatement ) {
                fmt.appendIndent()
                fmt.append('main:\n')
            }
            fmt.visit(node.main)
        }
        if( node.emits instanceof BlockStatement ) {
            fmt.appendNewLine()
            fmt.appendIndent()
            fmt.append('emit:\n')
            visitWorkflowEmits(asBlockStatements(node.emits))
        }
        if( node.publishers instanceof BlockStatement ) {
            fmt.appendNewLine()
            fmt.appendIndent()
            fmt.append('publish:\n')
            fmt.visit(node.publishers)
        }
        fmt.decIndent()
        fmt.append('}\n')
    }

    protected void visitWorkflowEmits(List<Statement> emits) {
        final alignmentWidth = options.harshilAlignment()
            ? getWorkflowEmitWidth(emits)
            : 0

        for( final stmt : emits ) {
            final stmtX = (ExpressionStatement)stmt
            if( stmtX.expression instanceof BinaryExpression ) {
                final binX = (BinaryExpression)stmtX.expression
                final varX = (VariableExpression)binX.getLeftExpression()
                fmt.appendIndent()
                fmt.visit(varX)
                if( alignmentWidth > 0 ) {
                    final padding = alignmentWidth - varX.name.length()
                    fmt.append(' ' * padding)
                }
                fmt.append(' = ')
                fmt.visit(binX.getRightExpression())
                fmt.appendNewLine()
            }
            else {
                fmt.visit(stmt)
            }
        }
    }

    protected int getWorkflowEmitWidth(List<Statement> emits) {
        if( emits.size() == 1 )
            return 0

        int maxWidth = 0
        for( final stmt : emits ) {
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
        fmt.appendComments(node)
        fmt.append('output {\n')
        fmt.incIndent()
        visitOutputBody(node.body)
        fmt.decIndent()
        fmt.append('}\n')
    }

    protected void visitOutputBody(Statement body) {
        asDirectives(body).forEach((call) -> {
            // treat as target definition
            final code = asDslBlock(call, 1)
            if( code != null ) {
                fmt.appendNewLine()
                fmt.appendIndent()
                fmt.visit(call.getMethod())
                fmt.append(' {\n')
                fmt.incIndent()
                visitTargetBody(code)
                fmt.decIndent()
                fmt.appendIndent()
                fmt.append('}\n')
                return
            }

            // treat as regular directive
            visitDirective(call)
        });
    }

    protected void visitTargetBody(BlockStatement block) {
        asDirectives(block).forEach((call) -> {
            // treat as index definition
            final name = call.getMethodAsString()
            if( name == 'index' ) {
                final code = asDslBlock(call, 1)
                if( code != null ) {
                    fmt.appendNewLine()
                    fmt.appendIndent()
                    fmt.append(name)
                    fmt.append(' {\n')
                    fmt.incIndent()
                    visitDirectives(code)
                    fmt.decIndent()
                    fmt.appendIndent()
                    fmt.append('}\n')
                    return
                }
            }

            // treat as regular directive
            visitDirective(call)
        });
    }

    protected void visitDirectives(Statement statement) {
        asDirectives(statement).forEach(this::visitDirective);
    }

    protected void visitDirective(MethodCallExpression call) {
        fmt.appendIndent()
        fmt.append(call.getMethodAsString())
        fmt.append(' ')
        fmt.visitArguments(asMethodCallArguments(call), hasNamedArgs(call), false)
        fmt.appendNewLine()
    }

}
