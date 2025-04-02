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
package nextflow.lsp.services.script;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import nextflow.lsp.ast.LanguageServerASTUtils;
import nextflow.lsp.services.CallHierarchyProvider;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.lsp.util.Logger;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/**
 * Provide the hierarchy of incoming/outgoing calls
 * for a given method or method call in a script.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptCallHierarchyProvider implements CallHierarchyProvider {

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;

    public ScriptCallHierarchyProvider(ScriptAstCache ast) {
        this.ast = ast;
    }

    /**
     * Prepare the call hierarchy item for a given method or method call.
     *
     * The client is expected to make subsequent requests for
     * incoming/outgoing calls when the call hierarchy item is expanded.
     *
     * @param textDocument
     * @param position
     */
    @Override
    public List<CallHierarchyItem> prepare(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while preparing call hierarchy");
            return Collections.emptyList();
        }

        var uri = URI.create(textDocument.getUri());
        var offsetNode = asMethodOrCallX(ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter()));
        if( offsetNode == null )
            return Collections.emptyList();

        var range = LanguageServerUtils.astNodeToRange(offsetNode);
        var selectionRange = range;
        var item = new CallHierarchyItem(
            getMethodOrCallName(offsetNode),
            SymbolKind.Method,
            textDocument.getUri(),
            range,
            selectionRange);
        return List.of(item);
    }

    private ASTNode asMethodOrCallX(ASTNode node) {
        if( node instanceof MethodNode )
            return node;
        if( node instanceof MethodCallExpression )
            return node;
        return null;
    }

    private String getMethodOrCallName(ASTNode node) {
        if( node instanceof MethodNode mn )
            return mn.getName() != null ? mn.getName() : "<entry>";
        if( node instanceof MethodCallExpression mce )
            return mce.getMethodAsString();
        return null;
    }

    /**
     * Get the list of callers of a given method and the selection
     * range of each call.
     *
     * @param item
     */
    @Override
    public List<CallHierarchyIncomingCall> incomingCalls(CallHierarchyItem item) {
        if( ast == null ) {
            log.error("ast cache is empty while providing incoming calls");
            return Collections.emptyList();
        }

        var uri = URI.create(item.getUri());
        var position = item.getRange().getStart();
        var offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
        if( offsetNode == null )
            return Collections.emptyList();

        var references = LanguageServerASTUtils.getReferences(offsetNode, ast, false);
        var referencesMap = new HashMap<MethodNode, List<Range>>();
        references.forEachRemaining((refNode) -> {
            if( !(refNode instanceof MethodCallExpression || refNode instanceof VariableExpression) )
                return;
            var fromNode = getEnclosingMethod(refNode);
            if( !referencesMap.containsKey(fromNode) )
                referencesMap.put(fromNode, new ArrayList<>());
            var range = LanguageServerUtils.astNodeToRange(refNode);
            referencesMap.get(fromNode).add(range);
        });

        var result = new ArrayList<CallHierarchyIncomingCall>();
        for( var fromNode : referencesMap.keySet() ) {
            var fromUri = ast.getURI(fromNode);
            var range = LanguageServerUtils.astNodeToRange(fromNode);
            var selectionRange = range;
            var from = new CallHierarchyItem(
                fromNode.getName() != null ? fromNode.getName() : "<entry>",
                SymbolKind.Method,
                fromUri.toString(),
                range,
                selectionRange);
            var fromRanges = referencesMap.get(fromNode);
            result.add(new CallHierarchyIncomingCall(from, fromRanges));
        }
        return result;
    }

    private MethodNode getEnclosingMethod(ASTNode node) {
        ASTNode current = node;
        while( current != null ) {
            if( current instanceof MethodNode mn )
                return mn;
            current = ast.getParent(current);
        }
        return null;
    }

    /**
     * Get the list methods that are called by a given method and
     * the selection range of each call.
     *
     * @param item
     */
    @Override
    public List<CallHierarchyOutgoingCall> outgoingCalls(CallHierarchyItem item) {
        if( ast == null ) {
            log.error("ast cache is empty while providing outgoing calls");
            return Collections.emptyList();
        }

        var uri = URI.create(item.getUri());
        var position = item.getRange().getStart();
        var offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
        if( offsetNode == null )
            return Collections.emptyList();

        var fromNode = LanguageServerASTUtils.getDefinition(offsetNode);
        if( !(fromNode instanceof MethodNode) )
            return Collections.emptyList();

        var sourceUnit = ast.getSourceUnit(uri);
        var visitor = new OutgoingCallsVisitor(sourceUnit);
        visitor.visit((MethodNode) fromNode);

        var callsMap = new HashMap<String, MethodCallExpression>();
        var rangesMap = new HashMap<String, List<Range>>();
        for( var call : visitor.getOutgoingCalls() ) {
            var name = call.getMethodAsString();
            callsMap.put(name, call);
            if( !rangesMap.containsKey(name) )
                rangesMap.put(name, new ArrayList<>());
            var range = LanguageServerUtils.astNodeToRange(call.getMethod());
            rangesMap.get(name).add(range);
        }

        var result = new ArrayList<CallHierarchyOutgoingCall>();
        for( var name : callsMap.keySet() ) {
            var call = callsMap.get(name);
            var toUri = ast.getURI(call);
            var range = LanguageServerUtils.astNodeToRange(call);
            var selectionRange = range;
            var to = new CallHierarchyItem(
                name,
                SymbolKind.Method,
                toUri.toString(),
                range,
                selectionRange);
            var fromRanges = rangesMap.get(name);
            result.add(new CallHierarchyOutgoingCall(to, fromRanges));
        }

        return result;
    }

    private static class OutgoingCallsVisitor extends ClassCodeVisitorSupport {

        private SourceUnit sourceUnit;

        private List<MethodCallExpression> outgoingCalls = new ArrayList<>();

        public OutgoingCallsVisitor(SourceUnit sourceUnit) {
            this.sourceUnit = sourceUnit;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }

        public void visit(MethodNode node) {
            if( node instanceof ProcessNode pn )
                visit(pn.exec);
            else if( node instanceof WorkflowNode wn )
                visit(wn.main);
            else
                visit(node.getCode());
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression node) {
            visit(node.getObjectExpression());
            visit(node.getArguments());

            if( node.isImplicitThis() )
                outgoingCalls.add(node);
        }

        public List<MethodCallExpression> getOutgoingCalls() {
            return outgoingCalls;
        }
    }

}
