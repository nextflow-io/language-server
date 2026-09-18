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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import nextflow.lsp.ast.LanguageServerASTUtils;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.WorkflowNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.MethodCallExpression;

/**
 * Determine the fully qualified names under which a process is invoked.
 *
 * At runtime a process is renamed with the chain of enclosing workflow names,
 * and `withName` selectors are matched against that name as well as the
 * process name. The entry workflow is unnamed, so it contributes no prefix.
 * See BindableDef.invoke_a in the Nextflow runtime.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class CallPathVisitor {

    private static final String SCOPE_SEP = ":";

    private final ScriptAstCache ast;

    /** Guards against a cycle in the call graph, which Nextflow rejects anyway. */
    private final Deque<MethodNode> stack = new ArrayDeque<>();

    private final Set<String> names = new LinkedHashSet<>();

    private MethodCallExpression target;

    CallPathVisitor(ScriptAstCache ast) {
        this.ast = ast;
    }

    /**
     * Get the qualified names of a specific process invocation.
     *
     * @param call
     */
    List<String> namesOf(MethodCallExpression call) {
        this.target = call;
        for( var uri : ast.getUris() ) {
            var sn = ast.getScriptNode(uri);
            if( sn != null && sn.getEntry() != null )
                walk(sn.getEntry(), "");
        }
        return List.copyOf(names);
    }

    private void walk(MethodNode node, String prefix) {
        if( !stack.isEmpty() && stack.contains(node) )
            return;
        stack.push(node);
        for( var call : new OutgoingCallsVisitor().apply(node) ) {
            var callee = LanguageServerASTUtils.getDefinition(call);
            // the name at the call site is the include alias, which is what
            // the runtime uses to build the qualified name
            var name = call.getMethodAsString();
            if( name == null )
                continue;
            var qualifiedName = prefix.isEmpty() ? name : prefix + SCOPE_SEP + name;
            if( callee instanceof WorkflowNode wn )
                walk(wn, qualifiedName);
            else if( callee instanceof ProcessNode && call == target )
                names.add(qualifiedName);
        }
        stack.pop();
    }

}
