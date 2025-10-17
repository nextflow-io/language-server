/*
 * Copyright 2024-2025, Seqera Labs
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
package nextflow.lsp.services.script.dag;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

import groovy.lang.Tuple3;
import nextflow.lsp.services.script.ScriptAstCache;
import nextflow.script.ast.ASTNodeMarker;
import nextflow.script.ast.AssignmentExpression;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ProcessNodeV1;
import nextflow.script.ast.ProcessNodeV2;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.ScriptVisitorSupport;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;

import static nextflow.script.ast.ASTUtils.*;
import static org.codehaus.groovy.ast.tools.GeneralUtils.*;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 * @author Erik Danielsson <danielsson.erik.0@gmail.com>
 */
public class DataflowVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    private ScriptAstCache ast;

    private boolean verbose;

    private Map<String,Graph> graphs = new HashMap<>();

    private Stack<Set<Node>> stackPreds = new Stack<>();

    private VariableContext vc = new VariableContext();

    public DataflowVisitor(SourceUnit sourceUnit, ScriptAstCache ast, boolean verbose) {
        this.sourceUnit = sourceUnit;
        this.ast = ast;
        this.verbose = verbose;

        stackPreds.push(new HashSet<>());
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

        for( var wn : scriptNode.getWorkflows() )
            visitWorkflow(wn);
    }

    public Graph getGraph(String name) {
        return graphs.get(name);
    }

    // script declarations

    private Graph current;

    private boolean inEntry;

    @Override
    public void visitWorkflow(WorkflowNode node) {
        current = new Graph();
        inEntry = node.isEntry();
        var name = node.isEntry() ? "<entry>" : node.getName();
        visitWorkflowTakes(node, current.inputs);
        visit(node.main);
        var outputs = node.isEntry() ? node.publishers : node.emits;
        visitWorkflowOutputs(outputs, current.outputs);
        graphs.put(name, current);
        inEntry = false;
        current = null;
    }

    private void visitWorkflowTakes(WorkflowNode node, Map<String,Node> result) {
        for( var take : node.getParameters() ) {
            var name = take.getName();
            var dn = addNode(name, Node.Type.NAME, take);
            dn.verbose = false;
            vc.putSymbol(name, dn);
            result.put(name, dn);
        }
    }

    private void visitWorkflowOutputs(Statement outputs, Map<String,Node> result) {
        for( var stmt : asBlockStatements(outputs) ) {
            var emit = ((ExpressionStatement) stmt).getExpression();
            String name;
            if( emit instanceof VariableExpression ve ) {
                name = ve.getName();
            }
            else if( emit instanceof AssignmentExpression assign ) {
                var target = (VariableExpression) assign.getLeftExpression();
                name = target.getName();
                visit(emit);
            }
            else {
                name = "$out";
                visit(new AssignmentExpression(varX(name), emit));
            }
            var dn = getSymbol(name);
            if( dn != null )
                dn.verbose = false;
            else
                System.err.println("missing output: " + name);
            result.put(name, dn);
        }
    }

    // statements

    @Override
    public void visitIfElse(IfStatement node) {
        // visit the conditional expression
        var controlPreds = visitWithPreds(node.getBooleanExpression());
        var controlDn = current.addNode("", Node.Type.CONTROL, null, controlPreds);

        // visit the if branch
        vc.pushScope();
        current.pushSubgraph(controlDn);
        visitWithPreds(node.getIfBlock());
        var ifSubgraph = current.popSubgraph();
        var ifScope = vc.popScope();

        // visit the else branch
        Subgraph elseSubgraph;
        Map<String,Variable> elseScope;

        if( !node.getElseBlock().isEmpty() ) {
            vc.pushScope();
            current.pushSubgraph(controlDn);
            visitWithPreds(node.getElseBlock());
            elseSubgraph = current.popSubgraph();
            elseScope = vc.popScope();
        }
        else {
            // if there is no else branch, then the set of active symbols
            // after the if statement is the union of the active symbols
            // from before the if and the active symbols in the if
            elseSubgraph = null;
            elseScope = vc.peekScope();
        }

        // apply variables from if and else scopes to current scope
        var outputs = vc.mergeConditionalScopes(ifScope, elseScope);

        for( var name : outputs ) {
            var preds = vc.getSymbolPreds(name);
            if( preds.size() > 1 ) {
                var dn = current.addNode(name, Node.Type.NAME, null, preds);
                vc.putSymbol(name, dn);
            }
        }

        // hide if-else statement if both subgraphs are empty
        if( !verbose && ifSubgraph.isVerbose() && (elseSubgraph == null || elseSubgraph.isVerbose()) ) {
            controlDn.verbose = true;
            for( var name : outputs )
                getSymbol(name).preds.addAll(controlPreds);
        }
    }

    // expressions

    @Override
    public void visitMethodCallExpression(MethodCallExpression node) {
        var name = node.getMethodAsString();
        if( "set".equals(name) && !node.isImplicitThis() ) {
            var code = asDslBlock(node, 1);
            if( code != null && !code.getStatements().isEmpty() ) {
                var target = asVarX(code.getStatements().get(0));
                if( target != null ) {
                    visit(new AssignmentExpression(target, node.getObjectExpression()));
                    return;
                }
            }
        }

        var defNode = (MethodNode) node.getNodeMetaData(ASTNodeMarker.METHOD_TARGET);
        if( defNode instanceof WorkflowNode || defNode instanceof ProcessNode ) {
            var preds = visitWithPreds(node.getArguments());
            var dn = addNode(name, Node.Type.OPERATOR, defNode, preds);
            vc.putSymbol(name, dn);
            return;
        }

        super.visitMethodCallExpression(node);
    }

    @Override
    public void visitBinaryExpression(BinaryExpression node) {
        if( node instanceof AssignmentExpression ) {
            visitAssignment(node, false);
            return;
        }
        if( node.getOperation().getType() == Types.PIPE ) {
            visitPipeline(node);
            return;
        }

        super.visitBinaryExpression(node);
    }

    private void visitAssignment(BinaryExpression node, boolean isLocal) {
        var preds = visitWithPreds(node.getRightExpression());
        var targets = getAssignmentTargets(node.getLeftExpression());
        for( var name : targets ) {
            var dn = addNode(name, Node.Type.NAME, null, preds);
            vc.putSymbol(name, dn, isLocal);
        }
    }

    private Set<String> getAssignmentTargets(Expression node) {
        // e.g. (x, y, z) = xyz
        if( node instanceof TupleExpression te ) {
            return te.getExpressions().stream()
                .map(el -> getAssignmentTarget(el).getName())
                .collect(Collectors.toSet());
        }
        else {
            return Set.of(getAssignmentTarget(node).getName());
        }
    }

    private VariableExpression getAssignmentTarget(Expression node) {
        // e.g. v = 123
        if( node instanceof VariableExpression ve )
            return ve;
        // e.g. obj.prop = 123
        if( node instanceof PropertyExpression pe )
            return getAssignmentTarget(pe.getObjectExpression());
        // e.g. list[1] = 123 OR map['a'] = 123
        if( node instanceof BinaryExpression be && be.getOperation().getType() == Types.LEFT_SQUARE_BRACKET )
            return getAssignmentTarget(be.getLeftExpression());
        return null;
    }

    private void visitPipeline(BinaryExpression node) {
        var lhs = node.getLeftExpression();
        var rhs = node.getRightExpression();

        // x | f => f(x)
        if( rhs instanceof VariableExpression ve ) {
            var defNode = asMethodVariable(ve.getAccessedVariable());
            if( defNode instanceof WorkflowNode || defNode instanceof ProcessNode ) {
                var label = defNode.getName();
                var preds = visitWithPreds(lhs);
                var dn = addNode(label, Node.Type.OPERATOR, defNode, preds);
                vc.putSymbol(label, dn);
                return;
            }
        }

        super.visitBinaryExpression(node);
    }

    @Override
    public void visitDeclarationExpression(DeclarationExpression node) {
        visitAssignment(node, true);
    }

    @Override
    public void visitTernaryExpression(TernaryExpression node) {
        var controlPreds = visitWithPreds(node.getBooleanExpression());
        var controlDn = current.addNode("", Node.Type.CONTROL, null, controlPreds);

        current.pushSubgraph(controlDn);
        var truePreds = visitWithPreds(node.getTrueExpression());
        var trueSubgraph = current.popSubgraph();
        currentPreds().addAll(truePreds);

        current.pushSubgraph(controlDn);
        var falsePreds = visitWithPreds(node.getFalseExpression());
        var falseSubgraph = current.popSubgraph();
        currentPreds().addAll(falsePreds);

        // hide ternary expression if both subgraphs are empty
        if( trueSubgraph.isVerbose() && falseSubgraph.isVerbose() ) {
            controlDn.verbose = true;
            currentPreds().addAll(controlPreds);
        }
    }

    @Override
    public void visitClosureExpression(ClosureExpression node) {
        // skip closures since they can't contain dataflow logic
    }

    @Override
    public void visitPropertyExpression(PropertyExpression node) {
        if( isEntryParam(node) ) {
            var name = node.getPropertyAsString();
            if( !current.inputs.containsKey(name) )
                current.inputs.put(name, addNode(name, Node.Type.NAME, null));
            var dn = current.inputs.get(name);
            dn.verbose = false;
            currentPreds().add(dn);
            return;
        }

        var result = asMethodOutput(node);
        if( result != null ) {
            var mn = result.getV1();
            var label = result.getV2();
            var propName = result.getV3();
            if( mn instanceof ProcessNodeV2 pn ) {
                visitProcessOut(pn, label, propName);
                return;
            }
            if( mn instanceof ProcessNodeV1 pn ) {
                visitProcessOut(pn, label, propName);
                return;
            }
            if( mn instanceof WorkflowNode wn ) {
                visitWorkflowOut(wn, label, propName);
                return;
            }
        }

        super.visitPropertyExpression(node);
    }

    private boolean isEntryParam(PropertyExpression node) {
        return inEntry && node.getObjectExpression() instanceof VariableExpression ve && "params".equals(ve.getName());
    }

    private Tuple3<MethodNode,String,String> asMethodOutput(PropertyExpression node) {
        // named output e.g. PROC.out.foo
        if( node.getObjectExpression() instanceof PropertyExpression pe ) {
            if( pe.getObjectExpression() instanceof VariableExpression ve && "out".equals(pe.getPropertyAsString()) ) {
                var mn = asMethodVariable(ve.getAccessedVariable());
                if( mn != null )
                    return new Tuple3(mn, ve.getName(), node.getPropertyAsString());
            }
        }
        // single output e.g. PROC.out
        else if( node.getObjectExpression() instanceof VariableExpression ve && "out".equals(node.getPropertyAsString()) ) {
            var mn = asMethodVariable(ve.getAccessedVariable());
            if( mn != null )
                return new Tuple3(mn, ve.getName(), null);
        }
        return null;
    }

    private void visitProcessOut(ProcessNodeV2 process, String label, String propName) {
        if( propName == null ) {
            addOperatorPred(label, process);
            return;
        }
        asBlockStatements(process.outputs).stream()
            .map(stmt -> ((ExpressionStatement) stmt).getExpression())
            .filter((output) -> {
                var outputName = typedOutputName(output);
                return propName.equals(outputName);
            })
            .findFirst()
            .ifPresent((call) -> {
                addOperatorPred(label, process);
            });
    }

    private void visitProcessOut(ProcessNodeV1 process, String label, String propName) {
        if( propName == null ) {
            addOperatorPred(label, process);
            return;
        }
        asDirectives(process.outputs)
            .filter((call) -> {
                var emitName = processEmitName(call);
                return propName.equals(emitName);
            })
            .findFirst()
            .ifPresent((call) -> {
                addOperatorPred(label, process);
            });
    }

    private String processEmitName(MethodCallExpression output) {
        return Optional.of(output)
            .flatMap(call -> Optional.ofNullable(asNamedArgs(call)))
            .flatMap(namedArgs ->
                namedArgs.stream()
                    .filter(entry -> "emit".equals(entry.getKeyExpression().getText()))
                    .findFirst()
            )
            .flatMap(entry -> Optional.ofNullable(
                entry.getValueExpression() instanceof VariableExpression ve ? ve.getName() : null
            ))
            .orElse(null);
    }

    private void visitWorkflowOut(WorkflowNode workflow, String label, String propName) {
        if( propName == null ) {
            addOperatorPred(label, workflow);
            return;
        }
        asBlockStatements(workflow.emits).stream()
            .map(stmt -> ((ExpressionStatement) stmt).getExpression())
            .filter((emit) -> {
                var emitName = typedOutputName(emit);
                return propName.equals(emitName);
            })
            .findFirst()
            .ifPresent((call) -> {
                addOperatorPred(label, workflow);
            });
    }

    private String typedOutputName(Expression emit) {
        if( emit instanceof VariableExpression ve ) {
            return ve.getName();
        }
        else if( emit instanceof AssignmentExpression assign ) {
            var target = (VariableExpression) assign.getLeftExpression();
            return target.getName();
        }
        return null;
    }

    private void addOperatorPred(String label, ASTNode an) {
        var dn = getSymbol(label);
        if( dn != null )
            currentPreds().add(dn);
        else
            vc.putSymbol(label, addNode(label, Node.Type.OPERATOR, an));
    }

    @Override
    public void visitVariableExpression(VariableExpression node) {
        var name = node.getName();
        var dn = getSymbol(name);
        if( dn != null )
            currentPreds().add(dn);
    }

    // helpers

    private Node getSymbol(String name) {
        var preds = vc.getSymbolPreds(name);
        if( preds.isEmpty() )
            return null;
        if( preds.size() > 1 )
            System.err.println("unmerged symbol " + name + " " + preds);
        return preds.iterator().next();
    }

    private Set<Node> currentPreds() {
        return stackPreds.peek();
    }

    private Set<Node> visitWithPreds(ASTNode... nodes) {
        return visitWithPreds(Arrays.asList(nodes));
    }

    private Set<Node> visitWithPreds(Collection<? extends ASTNode> nodes) {
        // traverse a set of nodes and extract predecessor nodes
        stackPreds.push(new HashSet<>());

        for( var node : nodes ) {
            if( node != null )
                node.visit(this);
        }

        return stackPreds.pop();
    }

    private Node addNode(String label, Node.Type type, ASTNode an, Set<Node> preds) {
        var uri = ast.getURI(an);
        var dn = current.addNode(label, type, uri, preds);
        currentPreds().add(dn);
        return dn;
    }

    private Node addNode(String label, Node.Type type, ASTNode an) {
        return addNode(label, type, an, new HashSet<>());
    }

}
