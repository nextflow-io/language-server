/*
 * Copyright 2024-2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
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

import groovy.lang.Tuple2;
import groovy.lang.Tuple3;
import nextflow.lsp.ast.LanguageServerASTUtils;
import nextflow.lsp.services.script.ScriptAstCache;
import nextflow.script.ast.ASTNodeMarker;
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

import static nextflow.script.ast.ASTUtils.*;
import static org.codehaus.groovy.ast.tools.GeneralUtils.*;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */

class Variable {
    private int defintionDepth;
    private Set<Node> activeInstances;

    public Variable(Node activeInstance, int defintionDepth) {
        this.activeInstances = new HashSet<>();
        activeInstances.add(activeInstance);
        this.defintionDepth = defintionDepth;
    }

    private Variable(Set<Node> activeInstances, int defintionDepth) {
        this.activeInstances = new HashSet<>(activeInstances);
        this.defintionDepth = defintionDepth;
    }

    public Variable shallowCopy() {
        return new Variable(activeInstances, defintionDepth);
    }

    public boolean isLocal(int currDepth) {
        return defintionDepth == currDepth;
    }

    public Set<Node> getActiveInstances() {
        return activeInstances;
    }

    public void setActiveInstance(Node dn) {
        activeInstances.clear();
        activeInstances.add(dn);
    }

    public Variable union(Variable other) {
        var unionInstances = new HashSet<>(activeInstances);
        unionInstances.addAll(other.getActiveInstances());
        return new Variable(unionInstances, defintionDepth);
    }
}

public class DataflowVisitor extends ScriptVisitorSupport {

    private SourceUnit sourceUnit;

    private ScriptAstCache ast;

    private Map<String, Graph> graphs = new HashMap<>();

    private Stack<Set<Node>> stackPreds = new Stack<>();

    private Stack<Map<String, Variable>> conditionalScopes = new Stack<>();

    private int currentDepth = 0;

    public DataflowVisitor(SourceUnit sourceUnit, ScriptAstCache ast) {
        this.sourceUnit = sourceUnit;
        this.ast = ast;

        stackPreds.add(new HashSet<>());
        conditionalScopes.push(new HashMap<>());
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

    public Set<Node> getSymbolConditionalScope(String name) {
        // get a variable node from the name table
        var scope = conditionalScopes.peek();
        if( scope.containsKey(name) ) {
            return scope.get(name).getActiveInstances();
        } else {
            return null;
        }
    }

    public void putSymbolConditionalScope(String name, Node dn) {
        // Put a symbol into the currently active symbol table
        var scope = conditionalScopes.peek();
        if( scope.containsKey(name) ) {
            // We have reassinged the variable, which means that all previous
            // definitions of it (in this scope) are dead. We can reuse the set
            // though
            Variable variable = scope.get(name);
            variable.setActiveInstance(dn);
        } else {
            // The symbols in not present from before so create an empty set
            Variable variable = new Variable(dn, currentDepth);
            scope.put(name, variable);
        }
    }

    public void pushConditionalScope(Map<String, Variable> previousSymbols) {
        // We want to add all symbols in the current scope
        // since they always enter both branches
        var newScope = new HashMap<String, Variable>();
        for( Map.Entry<String, Variable> entry : previousSymbols.entrySet() ) {
            newScope.put(entry.getKey(), entry.getValue().shallowCopy());
        }
        conditionalScopes.push(newScope);
        currentDepth += 1;
    }

    public Map<String, Variable> mergeConditionalScopes(Map<String, Variable> ifSymbols,
            Map<String, Variable> elseSymbols) {
        // Merge the set nodes corresponding to active symbols for the two
        // scopes
        // NOTE: Since we can define local variables with `def` we should have a
        // flag
        // which tells us
        // if this symbol is locally defined in this scope or defined outside
        // the scope.
        var merged = new HashMap<String, Variable>();
        // Add all keys from ifSymbols
        for( Map.Entry<String, Variable> entry : ifSymbols.entrySet() ) {
            String key = entry.getKey();
            Variable ifVariable = entry.getValue();
            if( elseSymbols.containsKey(key) ) {
                merged.put(key, ifVariable.union(elseSymbols.get(key)));
            } else {
                merged.put(key, ifVariable.shallowCopy());
            }
        }

        // Add remaining keys from elseSymbols (already handled common ones)
        for( Map.Entry<String, Variable> entry : elseSymbols.entrySet() ) {
            String key = entry.getKey();
            if( !merged.containsKey(key) ) {
                merged.put(key, entry.getValue().shallowCopy());
            }
        }

        return merged;
    }

    public Map<String, Variable> popConditionalScope() {
        currentDepth -= 1;
        return conditionalScopes.pop();
    }

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

    private void visitWorkflowTakes(WorkflowNode node, Map<String, Node> result) {
        for( var stmt : asBlockStatements(node.takes) ) {
            var name = asVarX(stmt).getName();
            var dn = addNode(name, Node.Type.NAME, stmt);
            putSymbolConditionalScope(name, dn);
            result.put(name, dn);
        }
    }

    private void visitWorkflowEmits(WorkflowNode node, Map<String, Node> result) {
        for( var stmt : asBlockStatements(node.emits) ) {
            var emit = ((ExpressionStatement) stmt).getExpression();
            String name;
            if( emit instanceof VariableExpression ve ) {
                name = ve.getName();
            } else if( emit instanceof AssignmentExpression assign ) {
                var target = (VariableExpression) assign.getLeftExpression();
                name = target.getName();
                visit(emit);
            } else {
                name = "$out";
                visit(new AssignmentExpression(varX(name), emit));
            }
            var dns = getSymbolConditionalScope(name);
            if( dns == null ) {
                System.err.println("missing emit: " + name);
                result.put(name, null);
            } else if( dns.size() > 1 ) {
                System.err.println("two many emits: " + name);
            } else {
                result.put(name, dns.iterator().next());
            }
        }
    }

    private void visitWorkflowPublishers(WorkflowNode node, Map<String, Node> result) {
        for( var stmt : asBlockStatements(node.publishers) ) {
            var es = (ExpressionStatement) stmt;
            var publisher = (BinaryExpression) es.getExpression();
            var target = asVarX(publisher.getLeftExpression());
            var source = publisher.getRightExpression();
            visit(new AssignmentExpression(target, source));
            var name = target.getName();
            var dns = getSymbolConditionalScope(name);
            if( dns.size() == 1 ) {
                result.put(name, dns.iterator().next());
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

        var defNode = (MethodNode) node.getNodeMetaData(ASTNodeMarker.METHOD_TARGET);
        if( defNode instanceof WorkflowNode || defNode instanceof ProcessNode ) {
            var preds = visitWithPreds(node.getArguments());
            var dn = addNode(name, Node.Type.OPERATOR, defNode, preds);
            putSymbolConditionalScope(name, dn);
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

    @Override
    public void visitIfElse(IfStatement node) {
        // First visit the conditional
        var preds = visitWithPreds(node.getBooleanExpression());
        var conditionalDn = addNode("<conditional>", Node.Type.CONDITIONAL, node, preds);

        // Then construct new symbol tables for each of the branches
        // Get the symbol table associated with the currently available global
        // variables
        var precedingScope = popConditionalScope();
        // Construct the scope for the if branch
        pushConditionalScope(precedingScope);
        // Push a new subgraph to keep track of the if branch
        current.pushSubgraph();
        current.putSubgraphPred(conditionalDn);
        visitWithPreds(node.getIfBlock());
        var ifScope = popConditionalScope();
        current.popSubgraph();
        if( !node.getElseBlock().isEmpty() ) {
            // We push a new symbol table to keep track of the
            // symbols in the else branch.
            pushConditionalScope(precedingScope);
            // Push a new subgraph to keep track of the else branch
            current.pushSubgraph();
            current.putSubgraphPred(conditionalDn);
            Set<Node> elsePreds = visitWithPreds(node.getElseBlock());
            elsePreds.add(conditionalDn);
            var elseScope = popConditionalScope();
            current.popSubgraph();
            var succedingScope = mergeConditionalScopes(ifScope, elseScope);
            pushConditionalScope(succedingScope);
        } else {
            // If there is only an if branch then the active symbols after the
            // statement
            // is the union of the active symbols from before the if and the
            // active symbols
            // in the if
            var succedingScope = mergeConditionalScopes(ifScope, precedingScope);
            pushConditionalScope(succedingScope);
        }
    }

    private void visitAssignment(BinaryExpression node) {
        var targetExpr = node.getLeftExpression();
        var sourceExpr = node.getRightExpression();
        if( targetExpr instanceof TupleExpression tupleExpr && sourceExpr instanceof ListExpression listExpr ) {
            // e.g. (x, y, z) = [1, 2, 3].
            // We need to keep track how what variable is assigned where
            List<Expression> targetExprList = tupleExpr.getExpressions();
            List<Expression> sourceExprList = listExpr.getExpressions();
            List<Tuple2<String, Set<Node>>> namesAndPreds = new ArrayList<>();
            // We need to wait with adding the nodes, so that we do not overwrite live
            // variable names in the source expression
            for( int i = 0; i < Math.min(targetExprList.size(), sourceExprList.size()); i++ ) {
                String name = getAssignmentTarget(targetExprList.get(i)).getName();
                var preds = visitWithPreds(sourceExprList.get(i));
                namesAndPreds.add(new Tuple2<>(name, preds));
            }
            for( var namePreds : namesAndPreds ) {
                var name = namePreds.getV1();
                var preds = namePreds.getV2();
                var dn = addNode(name, Node.Type.NAME, null, preds);
                putSymbolConditionalScope(name, dn);
            }
        } else {
            processAssignment(targetExpr, sourceExpr);
        }
    }

    private void processAssignment(Expression target, Expression source) {
        String name = getAssignmentTarget(target).getName();
        var preds = visitWithPreds(source);
        var dn = addNode(name, Node.Type.NAME, null, preds);
        putSymbolConditionalScope(name, dn);
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
                putSymbolConditionalScope(label, dn);
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

    private Tuple3<MethodNode, String, String> asMethodOutput(PropertyExpression node) {
        // named output e.g. PROC.out.foo
        if( node.getObjectExpression() instanceof PropertyExpression pe ) {
            if( pe.getObjectExpression() instanceof VariableExpression ve && "out".equals(pe.getPropertyAsString()) ) {
                var mn = asMethodVariable(ve.getAccessedVariable());
                if( mn != null )
                    return new Tuple3<MethodNode, String, String>(mn, ve.getName(), node.getPropertyAsString());
            }
        }
        // single output e.g. PROC.out
        else if( node.getObjectExpression() instanceof VariableExpression ve
                && "out".equals(node.getPropertyAsString()) ) {
            var mn = asMethodVariable(ve.getAccessedVariable());
            if( mn != null )
                return new Tuple3<MethodNode, String, String>(mn, ve.getName(), null);
        }
        return null;
    }

    private void visitProcessOut(ProcessNode process, String label, String propName) {
        if( propName == null ) {
            addOperatorPred(label, process);
            return;
        }
        asDirectives(process.outputs).filter((call) -> {
            var emitName = getProcessEmitName(call);
            return propName.equals(emitName);
        }).findFirst().ifPresent((call) -> {
            addOperatorPred(label, process);
        });
    }

    private String getProcessEmitName(MethodCallExpression output) {
        return Optional.of(output).flatMap(call -> Optional.ofNullable(asNamedArgs(call)))
                .flatMap(namedArgs -> namedArgs.stream()
                        .filter(entry -> "emit".equals(entry.getKeyExpression().getText())).findFirst())
                .flatMap(entry -> Optional
                        .ofNullable(entry.getValueExpression() instanceof VariableExpression ve ? ve.getName() : null))
                .orElse(null);
    }

    private void visitWorkflowOut(WorkflowNode workflow, String label, String propName) {
        if( propName == null ) {
            addOperatorPred(label, workflow);
            return;
        }
        asBlockStatements(workflow.emits).stream().map(stmt -> ((ExpressionStatement) stmt).getExpression())
                .filter((emit) -> {
                    var emitName = getWorkflowEmitName(emit);
                    return propName.equals(emitName);
                }).findFirst().ifPresent((call) -> {
                    addOperatorPred(label, workflow);
                });
    }

    private String getWorkflowEmitName(Expression emit) {
        if( emit instanceof VariableExpression ve ) {
            return ve.getName();
        } else if( emit instanceof AssignmentExpression assign ) {
            var target = (VariableExpression) assign.getLeftExpression();
            return target.getName();
        }
        return null;
    }

    private void addOperatorPred(String label, ASTNode an) {
        Set<Node> dns = getSymbolConditionalScope(label);
        if( dns != null ) {
            for( var dn : dns ) {
                currentPreds().add(dn);
            }
        } else {
            var dn = addNode(label, Node.Type.OPERATOR, an);
            putSymbolConditionalScope(label, dn);
        }
    }

    @Override
    public void visitVariableExpression(VariableExpression node) {
        var name = node.getName();
        Set<Node> dns = getSymbolConditionalScope(name);
        if( dns != null )
            for( Node dn : dns ) {
                currentPreds().add(dn);
            }
    }

    // helpers

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

    // private Node visitWithPred(ASTNode node) {
    // return visitWithPreds(node).stream().findFirst().orElse(null);
    // }

    private Node addNode(String label, Node.Type type, ASTNode an, Set<Node> preds) {
        var uri = ast.getURI(an);
        var dn = current.addNode(label, type, uri, preds);
        currentPreds().add(dn);
        return dn;
    }

    private Node addNode(String label, Node.Type type, ASTNode an) {
        var dn = addNode(label, type, an, new HashSet<>());
        return dn;
    }

}

class Subgraph {

    private int id;

    private List<Subgraph> children = new ArrayList<>();

    private Set<Node> members = new HashSet<>();

    private Set<Node> preds = new HashSet<>();

    public Subgraph(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public void addMember(Node n) {
        members.add(n);
    }

    public Set<Node> getMembers() {
        return members;
    }

    public void addChild(Subgraph s) {
        children.add(s);
    }

    public List<Subgraph> getChildren() {
        return children;
    }

    public Set<Node> getPreds() {
        return preds;
    }

    public void addPred(Node n) {
        preds.add(n);
    }
}

class Graph {

    public final Map<String, Node> inputs = new HashMap<>();

    public final Map<Integer, Node> nodes = new HashMap<>();

    public final Map<String, Node> outputs = new HashMap<>();

    private Stack<Map<String, Node>> scopes = new Stack<>();

    public Stack<Subgraph> activeSubgraphs = new Stack<>();

    private int currSubgraphId = 0;

    public Graph() {
        pushScope();
        pushSubgraph();
    }

    public void pushScope() {
        scopes.add(0, new HashMap<>());
    }

    public void popScope() {
        scopes.remove(0);
    }

    public void pushSubgraph() {
        activeSubgraphs.push(new Subgraph(currSubgraphId));
        currSubgraphId += 1;
    }

    public void popSubgraph() {
        var prevSubgraph = activeSubgraphs.pop();
        var currSubgraph = activeSubgraphs.peek();
        currSubgraph.addChild(prevSubgraph);
    }

    public void putToSubgraph(Node n) {
        activeSubgraphs.peek().addMember(n);
    }

    public void putSubgraphPred(Node n) {
        activeSubgraphs.peek().addPred(n);
    }

    public Node addNode(String label, Node.Type type, URI uri, Set<Node> preds) {
        // Check if the node already exists
        // var prevSymbol = getSymbol(label);
        // if( prevSymbol != null ) {
        // // If it exists, add the predecessors to the existing node
        // prevSymbol.addPredecessors(preds);
        // return prevSymbol;
        // }
        var id = nodes.size();
        var dn = new Node(id, label, type, uri, preds);
        nodes.put(id, dn);
        putToSubgraph(dn);
        return dn;
    }
}

class Node {
    public enum Type {
        NAME, OPERATOR, CONDITIONAL, IF, ELSE
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
