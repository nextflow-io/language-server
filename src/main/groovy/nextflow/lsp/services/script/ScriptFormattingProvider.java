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
package nextflow.lsp.services.script;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import nextflow.lsp.services.util.FormattingOptions;
import nextflow.lsp.services.util.Formatter;
import nextflow.lsp.services.FormattingProvider;
import nextflow.lsp.util.Logger;
import nextflow.lsp.util.Positions;
import nextflow.script.v2.AssignmentExpression;
import nextflow.script.v2.FeatureFlagNode;
import nextflow.script.v2.FunctionNode;
import nextflow.script.v2.IncludeNode;
import nextflow.script.v2.IncludeVariable;
import nextflow.script.v2.OutputNode;
import nextflow.script.v2.ProcessNode;
import nextflow.script.v2.ScriptNode;
import nextflow.script.v2.ScriptVisitorSupport;
import nextflow.script.v2.WorkflowNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.IOGroovyMethods;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

import static nextflow.script.v2.ASTHelpers.*;

/**
 * Provide formatting for a script.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptFormattingProvider implements FormattingProvider {

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;

    public ScriptFormattingProvider(ScriptAstCache ast) {
        this.ast = ast;
    }

    @Override
    public List<? extends TextEdit> formatting(URI uri, FormattingOptions options) {
        if( ast == null ) {
            log.error("ast cache is empty while providing formatting");
            return Collections.emptyList();
        }

        if( !ast.hasAST(uri) || ast.hasSyntaxErrors(uri) )
            return Collections.emptyList();

        var sourceUnit = ast.getSourceUnit(uri);
        String oldText;
        try {
            oldText = IOGroovyMethods.getText(sourceUnit.getSource().getReader());
        }
        catch( IOException e ) {
            return Collections.emptyList();
        }

        var range = new Range(new Position(0, 0), Positions.getPosition(oldText, oldText.length()));
        var visitor = new Visitor(sourceUnit, options, ast);
        visitor.visit();
        var newText = visitor.toString();

        return List.of( new TextEdit(range, newText) );
    }

    private static class Visitor extends ScriptVisitorSupport {

        private SourceUnit sourceUnit;

        private FormattingOptions options;

        private ScriptAstCache ast;

        private Formatter fmt;

        private int maxIncludeWidth = 0;

        public Visitor(SourceUnit sourceUnit, FormattingOptions options, ScriptAstCache ast) {
            this.sourceUnit = sourceUnit;
            this.options = options;
            this.ast = ast;
            this.fmt = new Formatter(options);
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }

        public void visit() {
            var moduleNode = sourceUnit.getAST();
            if( !(moduleNode instanceof ScriptNode) )
                return;
            var scriptNode = (ScriptNode) moduleNode;
            if( options.harshilAlignment() )
                maxIncludeWidth = getMaxIncludeWidth(scriptNode.getIncludes());
            if( scriptNode.getShebang() != null )
                fmt.append(scriptNode.getShebang());
            for( var featureFlag : scriptNode.getFeatureFlags() )
                visitFeatureFlag(featureFlag);
            for( var includeNode : scriptNode.getIncludes() )
                visitInclude(includeNode);
            if( scriptNode.getEntry() != null )
                visitWorkflow(scriptNode.getEntry());
            if( scriptNode.getOutput() != null )
                visitOutput(scriptNode.getOutput());
            for( var workflowNode : scriptNode.getWorkflows() ) {
                if( !workflowNode.isEntry() )
                    visitWorkflow(workflowNode);
            }
            for( var processNode : scriptNode.getProcesses() )
                visitProcess(processNode);
            for( var functionNode : scriptNode.getFunctions() )
                visitFunction(functionNode);
            for( var classNode : scriptNode.getClasses() ) {
                if( classNode.isEnum() )
                    visitEnum(classNode);
            }
        }

        protected int getMaxIncludeWidth(List<IncludeNode> includes) {
            int maxWidth = 0;
            for( var includeNode : includes ) {
                for( var module : includeNode.modules ) {
                    var width = getIncludeWidth(module);
                    if( maxWidth < width )
                        maxWidth = width;
                }
            }
            return maxWidth;
        }

        protected int getIncludeWidth(IncludeVariable module) {
            return module.alias != null
                ? module.name.length() + 4 + module.alias.length()
                : module.name.length();
        }

        public String toString() {
            return fmt.toString();
        }

        // script statements

        @Override
        public void visitFeatureFlag(FeatureFlagNode node) {
            fmt.appendLeadingComments(node);
            fmt.append(node.name);
            fmt.append(" = ");
            fmt.visit(node.value);
            fmt.appendNewLine();
        }

        @Override
        public void visitInclude(IncludeNode node) {
            var wrap = node.modules.size() > 1;
            fmt.appendLeadingComments(node);
            fmt.append("include {");
            if( wrap )
                fmt.incIndent();
            for( int i = 0; i < node.modules.size(); i++ ) {
                if( wrap ) {
                    fmt.appendNewLine();
                    fmt.appendIndent();
                }
                else {
                    fmt.append(' ');
                }
                var module = node.modules.get(i);
                fmt.append(module.name);
                if( module.alias != null ) {
                    fmt.append(" as ");
                    fmt.append(module.alias);
                }
                if( !wrap && options.harshilAlignment() ) {
                    var padding = maxIncludeWidth - getIncludeWidth(module);
                    fmt.append(" ".repeat(padding));
                }
                if( i + 1 < node.modules.size() )
                    fmt.append(" ;");
            }
            if( wrap ) {
                fmt.appendNewLine();
                fmt.decIndent();
            }
            else {
                fmt.append(' ');
            }
            fmt.append("} from ");
            fmt.visit(node.source);
            fmt.appendNewLine();
        }

        @Override
        public void visitEnum(ClassNode node) {
            fmt.appendLeadingComments(node);
            fmt.append("enum ");
            fmt.append(node.getName());
            fmt.append(" {\n");
            fmt.incIndent();
            for( var fn : node.getFields() ) {
                fmt.appendIndent();
                fmt.append(fn.getName());
                fmt.append(',');
                fmt.appendNewLine();
            }
            fmt.decIndent();
            fmt.append("}\n");
        }

        @Override
        public void visitFunction(FunctionNode node) {
            fmt.appendLeadingComments(node);
            fmt.append("def ");
            fmt.append(node.getName());
            fmt.append('(');
            fmt.visitParameters(node.getParameters());
            fmt.append(") {\n");
            fmt.incIndent();
            fmt.visit(node.getCode());
            fmt.decIndent();
            fmt.append("}\n");
        }

        @Override
        public void visitProcess(ProcessNode node) {
            fmt.appendLeadingComments(node);
            fmt.append("process ");
            fmt.append(node.getName());
            fmt.append(" {\n");
            fmt.incIndent();
            if( node.directives instanceof BlockStatement ) {
                visitDirectives(node.directives);
                fmt.appendNewLine();
            }
            if( node.inputs instanceof BlockStatement ) {
                fmt.appendIndent();
                fmt.append("input:\n");
                visitDirectives(node.inputs);
                fmt.appendNewLine();
            }
            if( node.outputs instanceof BlockStatement ) {
                fmt.appendIndent();
                fmt.append("output:\n");
                visitDirectives(node.outputs);
                fmt.appendNewLine();
            }
            if( !(node.when instanceof EmptyExpression) ) {
                fmt.appendIndent();
                fmt.append("when:\n");
                fmt.appendIndent();
                fmt.visit(node.when);
                fmt.append("\n\n");
            }
            fmt.appendIndent();
            fmt.append(node.type);
            fmt.append(":\n");
            fmt.visit(node.exec);
            if( !(node.stub instanceof EmptyStatement) ) {
                fmt.appendNewLine();
                fmt.appendIndent();
                fmt.append("stub:\n");
                fmt.visit(node.stub);
            }
            fmt.decIndent();
            fmt.append("}\n");
        }

        @Override
        public void visitWorkflow(WorkflowNode node) {
            fmt.appendLeadingComments(node);
            fmt.append("workflow");
            if( !node.isEntry() ) {
                fmt.append(' ');
                fmt.append(node.getName());
            }
            fmt.append(" {\n");
            fmt.incIndent();
            if( node.takes instanceof BlockStatement ) {
                fmt.appendIndent();
                fmt.append("take:\n");
                visitWorkflowTakes(asBlockStatements(node.takes));
                fmt.appendNewLine();
            }
            if( node.main instanceof BlockStatement ) {
                if( node.takes instanceof BlockStatement || node.emits instanceof BlockStatement || node.publishers instanceof BlockStatement ) {
                    fmt.appendIndent();
                    fmt.append("main:\n");
                }
                fmt.visit(node.main);
            }
            if( node.emits instanceof BlockStatement ) {
                fmt.appendNewLine();
                fmt.appendIndent();
                fmt.append("emit:\n");
                visitWorkflowEmits(asBlockStatements(node.emits));
            }
            if( node.publishers instanceof BlockStatement ) {
                fmt.appendNewLine();
                fmt.appendIndent();
                fmt.append("publish:\n");
                fmt.visit(node.publishers);
            }
            fmt.decIndent();
            fmt.append("}\n");
        }

        protected void visitWorkflowTakes(List<Statement> takes) {
            var alignmentWidth = options.harshilAlignment()
                ? getMaxParameterWidth(takes)
                : 0;

            for( var stmt : takes ) {
                var stmtX = (ExpressionStatement)stmt;
                var emit = stmtX.getExpression();
                var ve = (VariableExpression) emit;
                fmt.appendIndent();
                fmt.visit(ve);
                if( alignmentWidth > 0 ) {
                    var padding = alignmentWidth - ve.getName().length();
                    fmt.append(" ".repeat(padding));
                }
                fmt.appendTrailingComment(stmt);
                fmt.appendNewLine();
            }
        }

        protected void visitWorkflowEmits(List<Statement> emits) {
            var alignmentWidth = options.harshilAlignment()
                ? getMaxParameterWidth(emits)
                : 0;

            for( var stmt : emits ) {
                var stmtX = (ExpressionStatement)stmt;
                var emit = stmtX.getExpression();
                if( emit instanceof AssignmentExpression assign ) {
                    var ve = (VariableExpression)assign.getLeftExpression();
                    fmt.appendIndent();
                    fmt.visit(ve);
                    if( alignmentWidth > 0 ) {
                        var padding = alignmentWidth - ve.getName().length();
                        fmt.append(" ".repeat(padding));
                    }
                    fmt.append(" = ");
                    fmt.visit(assign.getRightExpression());
                    fmt.appendTrailingComment(stmt);
                    fmt.appendNewLine();
                }
                else if( emit instanceof VariableExpression ve ) {
                    fmt.appendIndent();
                    fmt.visit(ve);
                    if( alignmentWidth > 0 ) {
                        var padding = alignmentWidth - ve.getName().length();
                        fmt.append(" ".repeat(padding));
                    }
                    fmt.appendTrailingComment(stmt);
                    fmt.appendNewLine();
                }
                else {
                    fmt.visit(stmt);
                }
            }
        }

        protected int getMaxParameterWidth(List<Statement> statements) {
            if( statements.size() == 1 )
                return 0;

            int maxWidth = 0;
            for( var stmt : statements ) {
                var stmtX = (ExpressionStatement)stmt;
                var emit = stmtX.getExpression();
                int width = 0;
                if( emit instanceof VariableExpression ve ) {
                    width = ve.getName().length();
                }
                else if( emit instanceof AssignmentExpression assign ) {
                    var ve = (VariableExpression)assign.getLeftExpression();
                    width = ve.getName().length();
                }

                if( maxWidth < width )
                    maxWidth = width;
            }
            return maxWidth;
        }

        @Override
        public void visitOutput(OutputNode node) {
            fmt.appendLeadingComments(node);
            fmt.append("output {\n");
            fmt.incIndent();
            visitOutputBody(node.body);
            fmt.decIndent();
            fmt.append("}\n");
        }

        protected void visitOutputBody(Statement body) {
            asDirectives(body).forEach((call) -> {
                // treat as target definition
                var code = asDslBlock(call, 1);
                if( code != null ) {
                    fmt.appendNewLine();
                    fmt.appendIndent();
                    fmt.visit(call.getMethod());
                    fmt.append(" {\n");
                    fmt.incIndent();
                    visitTargetBody(code);
                    fmt.decIndent();
                    fmt.appendIndent();
                    fmt.append("}\n");
                    return;
                }

                // treat as regular directive
                visitDirective(call);
            });
        }

        protected void visitTargetBody(BlockStatement block) {
            asDirectives(block).forEach((call) -> {
                // treat as index definition
                var name = call.getMethodAsString();
                if( "index".equals(name) ) {
                    var code = asDslBlock(call, 1);
                    if( code != null ) {
                        fmt.appendNewLine();
                        fmt.appendIndent();
                        fmt.append(name);
                        fmt.append(" {\n");
                        fmt.incIndent();
                        visitDirectives(code);
                        fmt.decIndent();
                        fmt.appendIndent();
                        fmt.append("}\n");
                        return;
                    }
                }

                // treat as regular directive
                visitDirective(call);
            });
        }

        protected void visitDirectives(Statement statement) {
            asDirectives(statement).forEach(this::visitDirective);
        }

        protected void visitDirective(MethodCallExpression call) {
            fmt.appendIndent();
            fmt.append(call.getMethodAsString());
            fmt.append(' ');
            fmt.visitArguments(asMethodCallArguments(call), false);
            fmt.appendNewLine();
        }

    }

}
