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
import nextflow.lsp.ast.ASTUtils
import nextflow.lsp.services.CallHierarchyProvider
import nextflow.lsp.util.LanguageServerUtils
import nextflow.lsp.util.Logger
import nextflow.script.v2.ProcessNode
import nextflow.script.v2.WorkflowNode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Provide the hierarchy of incoming/outgoing calls
 * for a given method or method call in a script.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptCallHierarchyProvider implements CallHierarchyProvider {

    private static Logger log = Logger.instance

    private ScriptAstCache ast

    ScriptCallHierarchyProvider(ScriptAstCache ast) {
        this.ast = ast
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
    List<CallHierarchyItem> prepare(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while preparing call hierarchy")
            return Collections.emptyList()
        }

        final uri = URI.create(textDocument.getUri())
        final offsetNode = asMethodOrCallX(ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter()))
        if( !offsetNode )
            return Collections.emptyList()

        final range = LanguageServerUtils.astNodeToRange(offsetNode)
        final selectionRange = range
        final item = new CallHierarchyItem(
            getMethodOrCallName(offsetNode),
            SymbolKind.Method,
            textDocument.getUri(),
            range,
            selectionRange)
        return List.of(item)
    }

    private ASTNode asMethodOrCallX(ASTNode node) {
        if( node instanceof MethodNode )
            return node
        if( node instanceof ConstantExpression && ast.getParent(node) instanceof MethodCallExpression )
            return node
        return null
    }

    private String getMethodOrCallName(ASTNode node) {
        if( node instanceof MethodNode )
            return node.getName()
        if( node instanceof ConstantExpression )
            return node.getText()
        return null
    }

    /**
     * Get the list of callers of a given method and the selection
     * range of each call.
     *
     * @param item
     */
    @Override
    List<CallHierarchyIncomingCall> incomingCalls(CallHierarchyItem item) {
        if( ast == null ) {
            log.error("ast cache is empty while providing incoming calls")
            return Collections.emptyList()
        }

        final uri = URI.create(item.getUri())
        final position = item.getRange().getStart()
        final offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( !offsetNode )
            return Collections.emptyList()

        final references = ASTUtils.getReferences(offsetNode, ast, false)
        final Map<MethodNode, List<Range>> referencesMap = [:]
        for( final refNode : references ) {
            if( !(refNode instanceof ConstantExpression || refNode instanceof VariableExpression) )
                continue
            final fromNode = getTopLevelNode(refNode)
            if( !referencesMap.containsKey(fromNode) )
                referencesMap.put(fromNode, [])
            final range = LanguageServerUtils.astNodeToRange(refNode)
            referencesMap.get(fromNode).add(range)
        }

        final List<CallHierarchyIncomingCall> result = []
        for( final fromNode : referencesMap.keySet() ) {
            final fromUri = ast.getURI(fromNode)
            final range = LanguageServerUtils.astNodeToRange(fromNode)
            final selectionRange = range
            final from = new CallHierarchyItem(
                fromNode.getName() ?: '<entry>',
                SymbolKind.Method,
                fromUri.toString(),
                range,
                selectionRange)
            final fromRanges = referencesMap.get(fromNode)
            result.add(new CallHierarchyIncomingCall(from, fromRanges))
        }
        return result
    }

    private MethodNode getTopLevelNode(ASTNode node) {
        ASTNode current = node
        while( current != null ) {
            if( current instanceof MethodNode )
                return current
            current = ast.getParent(current)
        }
        return null
    }

    /**
     * Get the list methods that are called by a given method and
     * the selection range of each call.
     *
     * @param item
     */
    @Override
    List<CallHierarchyOutgoingCall> outgoingCalls(CallHierarchyItem item) {
        if( ast == null ) {
            log.error("ast cache is empty while providing outgoing calls")
            return Collections.emptyList()
        }

        final uri = URI.create(item.getUri())
        final position = item.getRange().getStart()
        final offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter())
        if( !offsetNode )
            return Collections.emptyList()

        final fromNode = ASTUtils.getDefinition(offsetNode, false, ast)
        if( fromNode !instanceof MethodNode )
            return Collections.emptyList()

        final sourceUnit = ast.getSourceUnit(uri)
        final visitor = new OutgoingCallsVisitor(sourceUnit)
        visitor.visit((MethodNode) fromNode)

        final Map<String, MethodCallExpression> callsMap = [:]
        final Map<String, List<Range>> rangesMap = [:]
        for( final call : visitor.getOutgoingCalls() ) {
            final name = call.getMethodAsString()
            callsMap.put(name, call)
            if( !rangesMap.containsKey(name) )
                rangesMap.put(name, [])
            final range = LanguageServerUtils.astNodeToRange(call.getMethod())
            rangesMap.get(name).add(range)
        }

        final List<CallHierarchyOutgoingCall> result = []
        for( final name : callsMap.keySet() ) {
            final call = callsMap.get(name)
            final toUri = ast.getURI(call)
            final range = LanguageServerUtils.astNodeToRange(call)
            final selectionRange = range
            final to = new CallHierarchyItem(
                name,
                SymbolKind.Method,
                toUri.toString(),
                range,
                selectionRange)
            final fromRanges = rangesMap.get(name)
            result.add(new CallHierarchyOutgoingCall(to, fromRanges))
        }

        return result
    }

    private static class OutgoingCallsVisitor extends ClassCodeVisitorSupport {

        private SourceUnit sourceUnit

        private List<MethodCallExpression> outgoingCalls = []

        OutgoingCallsVisitor(SourceUnit sourceUnit) {
            this.sourceUnit = sourceUnit
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit
        }

        void visit(MethodNode node) {
            if( node instanceof ProcessNode )
                visit(node.exec)
            else if( node instanceof WorkflowNode )
                visit(node.main)
            else
                visit(node.code)
        }

        @Override
        void visitMethodCallExpression(MethodCallExpression node) {
            visit(node.getObjectExpression())
            visit(node.getArguments())

            if( node.isImplicitThis() )
                outgoingCalls.add(node)
        }

        List<MethodCallExpression> getOutgoingCalls() {
            return outgoingCalls
        }
    }
}
