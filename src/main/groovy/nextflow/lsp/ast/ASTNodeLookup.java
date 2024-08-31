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
package nextflow.lsp.ast;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ASTNodeLookup {

    private Map<ASTNode, ASTNode> parents = new HashMap<>();

    private Stack<ASTNode> stack = new Stack<>();

    public void push(ASTNode node) {
        var isSynthetic = node instanceof AnnotatedNode an && an.isSynthetic();
        if( !isSynthetic ) {
            var parent = stack.size() > 0 ? stack.lastElement() : null;
            parents.put(node, parent);
        }

        stack.add(node);
    }

    public void pop() {
        stack.pop();
    }

    public Map<ASTNode, ASTNode> getParents() {
        return parents;
    }
}
