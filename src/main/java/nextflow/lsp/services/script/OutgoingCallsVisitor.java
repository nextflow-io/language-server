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

import java.util.ArrayList;
import java.util.List;

import nextflow.script.ast.ProcessNodeV1;
import nextflow.script.ast.ProcessNodeV2;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;

/**
 * Query the list of outgoing calls made by a workflow, process, or function.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class OutgoingCallsVisitor extends CodeVisitorSupport {

    private List<MethodCallExpression> outgoingCalls;

    public List<MethodCallExpression> apply(MethodNode node) {
        outgoingCalls = new ArrayList<>();
        if( node instanceof ProcessNodeV2 pn )
            visit(pn.exec);
        else if( node instanceof ProcessNodeV1 pn )
            visit(pn.exec);
        else if( node instanceof WorkflowNode wn )
            visit(wn.main);
        else
            visit(node.getCode());
        return outgoingCalls;
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression node) {
        visit(node.getObjectExpression());
        visit(node.getArguments());

        if( node.isImplicitThis() )
            outgoingCalls.add(node);
    }

    @Override
    public void visitShortTernaryExpression(ElvisOperatorExpression node) {
        visit(node.getTrueExpression());
        visit(node.getFalseExpression());
    }
}
