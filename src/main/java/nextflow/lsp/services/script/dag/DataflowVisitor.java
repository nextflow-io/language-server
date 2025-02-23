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
package nextflow.lsp.services.script.dag;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

import groovy.lang.Tuple3;
import nextflow.lsp.ast.ASTUtils;
import nextflow.lsp.services.script.ScriptAstCache;
import nextflow.script.ast.AssignmentExpression;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.ScriptVisitorSupport;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;

import static nextflow.script.ast.ASTHelpers.*;
import static org.codehaus.groovy.ast.tools.GeneralUtils.*;


/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class DataflowVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    private ScriptAstCache astCache;

    private Map<String,Graph> graphs = new HashMap<>();

    private Stack<Set<Node>> stackPreds = new Stack<>();

    public DataflowVisitor(SourceUnit sourceUnit, ScriptAstCache astCache) {
        this.sourceUnit = sourceUnit;
        this.astCache = astCache;

        stackPreds.add(new HashSet<>());
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
        if( node.isEntry() )
            visitWorkflowPublishers(node, current.outputs);
        else
            visitWorkflowEmits(node, current.outputs);
        graphs.put(name, current);
        inEntry = false;
        current = null;
    }

    private void visitWorkflowTakes(WorkflowNode node, Map<String,Node> result) {
        for( var stmt : asBlockStatements(node.takes) ) {
            var name = asVarX(stmt).getName();
            var dn = addNode(name, Node.Type.NAME, stmt);
            current.putSymbol(name, dn);
            result.put(name, dn);
        }
    }

    private void visitWorkflowEmits(WorkflowNode node, Map<String,Node> result) {
        for( var stmt : asBlockStatements(node.emits) ) {
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
            var dn = current.getSymbol(name);
            if( dn == null )
                System.err.println("missing emit: " + name);
            result.put(name, dn);
        }
    }

    private void visitWorkflowPublishers(WorkflowNode node, Map<String,Node> result) {
        for( var stmt : asBlockStatements(node.publishers) ) {
            var publisher = (BinaryExpression) ((ExpressionStatement) stmt).getExpression();
            var target = publisher.getRightExpression();
            if( target instanceof ConstantExpression ce ) {
                var name = ce.getText();
                var source = publisher.getLeftExpression();
                visit(new AssignmentExpression(varX(name), source));
                result.put(name, current.getSymbol(name));
            }
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

        var defNode = ASTUtils.getMethodFromCallExpression(node, astCache);
        if( defNode instanceof WorkflowNode || defNode instanceof ProcessNode ) {
            var preds = visitWithPreds(node.getArguments());
            current.putSymbol(name, addNode(name, Node.Type.OPERATOR, defNode, preds));
            return;
        }

        super.visitMethodCallExpression(node);
    }

    @Override
    public void visitBinaryExpression(BinaryExpression node) {
        if( node instanceof AssignmentExpression ) {
            visitAssignment(node);
            return;
        }
        if( node.getOperation().getType() == Types.PIPE ) {
            visitPipeline(node);
            return;
        }

        super.visitBinaryExpression(node);
    }

    private void visitAssignment(BinaryExpression node) {
        var preds = visitWithPreds(node.getRightExpression());
        var targets = getAssignmentTargets(node.getLeftExpression());
        for( var name : targets ) {
            var dn = addNode(name, Node.Type.NAME, null, preds);
            current.putSymbol(name, dn);
        }
    }

    private Set<String> getAssignmentTargets(Expression node) {
        // e.g. (x, y, z) = [1, 2, 3]
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
        if( rhs instanceof VariableExpression ) {
            var defNode = ASTUtils.getDefinition(rhs, astCache);
            if( defNode instanceof WorkflowNode || defNode instanceof ProcessNode ) {
                var label = ((MethodNode) defNode).getName();
                var preds = visitWithPreds(lhs);
                current.putSymbol(label, addNode(label, Node.Type.OPERATOR, defNode, preds));
                return;
            }
        }

        super.visitBinaryExpression(node);
    }

    @Override
    public void visitDeclarationExpression(DeclarationExpression node) {
        visitAssignment(node);
    }

    @Override
    public void visitClosureExpression(ClosureExpression node) {
    }

    @Override
    public void visitPropertyExpression(PropertyExpression node) {
        if( isEntryParam(node) ) {
            var name = node.getPropertyAsString();
            if( !current.inputs.containsKey(name) )
                current.inputs.put(name, addNode(name, Node.Type.NAME, null));
            var dn = current.inputs.get(name);
            currentPreds().add(dn);
            return;
        }

        var result = asMethodOutput(node);
        if( result != null ) {
            var mn = result.getV1();
            var label = result.getV2();
            var propName = result.getV3();
            if( mn instanceof ProcessNode pn ) {
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
                var defNode = ASTUtils.getDefinition(ve, astCache);
                if( defNode instanceof MethodNode mn )
                    return new Tuple3(mn, ve.getName(), node.getPropertyAsString());
            }
        }
        // single output e.g. PROC.out
        else if( node.getObjectExpression() instanceof VariableExpression ve && "out".equals(node.getPropertyAsString()) ) {
            var defNode = ASTUtils.getDefinition(ve, astCache);
            if( defNode instanceof MethodNode mn )
                return new Tuple3(mn, ve.getName(), null);
        }
        return null;
    }

    private void visitProcessOut(ProcessNode process, String label, String propName) {
        if( propName == null ) {
            addOperatorPred(label, process);
            return;
        }
        asDirectives(process.outputs)
            .filter((call) -> {
                var emitName = getProcessEmitName(call);
                return propName.equals(emitName);
            })
            .findFirst()
            .ifPresent((call) -> {
                addOperatorPred(label, process);
            });
    }

    private String getProcessEmitName(MethodCallExpression output) {
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
                var emitName = getWorkflowEmitName(emit);
                return propName.equals(emitName);
            })
            .findFirst()
            .ifPresent((call) -> {
                addOperatorPred(label, workflow);
            });
    }

    private String getWorkflowEmitName(Expression emit) {
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
        var dn = current.getSymbol(label);
        if( dn != null )
            currentPreds().add(dn);
        else
            current.putSymbol(label, addNode(label, Node.Type.OPERATOR, an));
    }

    @Override
    public void visitVariableExpression(VariableExpression node) {
        var name = node.getName();
        var dn = current.getSymbol(name);
        if( dn != null )
            currentPreds().add(dn);
    }

    // helpers

    private Set<Node> currentPreds() {
        return stackPreds.lastElement();
    }

    private Set<Node> visitWithPreds(ASTNode... nodes) {
        return visitWithPreds(Arrays.asList(nodes));
    }

    private Set<Node> visitWithPreds(Collection<? extends ASTNode> nodes) {
        // traverse a set of nodes and extract predecessor nodes
        stackPreds.add(new HashSet<>());

        for( var node : nodes ) {
            if( node != null )
                node.visit(this);
        }

        return stackPreds.pop();
    }

    private Node visitWithPred(ASTNode node) {
        return visitWithPreds(node).stream().findFirst().orElse(null);
    }

    private Node addNode(String label, Node.Type type, ASTNode an, Set<Node> preds) {
        var uri = astCache.getURI(an);
        var dn = current.addNode(label, type, uri, preds);
        currentPreds().add(dn);
        return dn;
    }

    private Node addNode(String label, Node.Type type, ASTNode an) {
        return addNode(label, type, an, new HashSet<>());
    }

}


class Graph {

    public final Map<String,Node> inputs = new HashMap<>();

    public final Map<Integer,Node> nodes = new HashMap<>();

    public final Map<String,Node> outputs = new HashMap<>();

    private List<Map<String,Node>> scopes = new ArrayList<>();

    public Graph() {
        pushScope();
    }

    public void pushScope() {
        scopes.add(0, new HashMap<>());
    }

    public void popScope() {
        scopes.remove(0);
    }

    public Node getSymbol(String name) {
        // get a variable node from the name table
        for( var scope : scopes )
            if( scope.containsKey(name) )
                return scope.get(name);

        return null;
    }

    public void putSymbol(String name, Node dn) {
        // put a variable node into the name table
        for( var scope : scopes ) {
            if( scope.containsKey(name) ) {
                scope.put(name, dn);
                return;
            }
        }

        scopes.get(0).put(name, dn);
    }

    public Node addNode(String label, Node.Type type, URI uri, Set preds) {
        var id = nodes.size();
        var dn = new Node(id, label, type, uri, preds);
        nodes.put(id, dn);
        return dn;
    }
}


class Node {
    public enum Type {
        NAME,
        OPERATOR
    }

    public final int id;
    public final String label;
    public final Type type;
    public final URI uri;
    public final Set<Node> preds;

    public Node(int id, String label, Type type, URI uri, Set<Node> preds) {
        this.id = id;
        this.label = label;
        this.type = type;
        this.uri = uri;
        this.preds = preds;
    }

    public void addPredecessors(Set<Node> preds) {
        this.preds.addAll(preds);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Node n && this.id == n.id;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        return String.format("id=%s,label='%s',type=%s", id, label, type);
    }
}
