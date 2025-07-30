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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 * @author Erik Danielsson <danielsson.erik.0@gmail.com>
 */
class VariableContext {

    private Stack<Map<String,Variable>> scopes = new Stack<>();

    public VariableContext() {
        scopes.push(new HashMap<>());
    }

    /**
     * Get the current scope.
     */
    public Map<String,Variable> peekScope() {
        return scopes.peek();
    }

    /**
     * Enter a new scope, inheriting all symbols defined
     * in the parent scope.
     */
    public void pushScope() {
        var newScope = new HashMap<String,Variable>();
        scopes.peek().forEach((name, variable) -> {
            newScope.put(name, variable.shallowCopy());
        });
        scopes.push(newScope);
    }

    /**
     * Exit the current scope.
     */
    public Map<String,Variable> popScope() {
        return scopes.pop();
    }

    /**
     * Get the current predecessors for a given symbol.
     *
     * @param name
     */
    public Set<Node> getSymbol(String name) {
        var scope = scopes.peek();
        return scope.containsKey(name)
            ? scope.get(name).preds
            : Collections.emptySet();
    }

    /**
     * Put a symbol into the current scope.
     *
     * @param name
     * @param dn
     * @param isLocal
     */
    public void putSymbol(String name, Node dn, boolean isLocal) {
        var scope = scopes.peek();
        if( scope.containsKey(name) ) {
            // reassign variable if it is already defined
            var variable = scope.get(name);
            variable.preds.clear();
            variable.preds.add(dn);
        }
        else {
            var depth = isLocal ? currentDepth() : 1;
            var preds = new HashSet<Node>();
            preds.add(dn);
            var variable = new Variable(depth, preds);
            scope.put(name, variable);
        }
    }

    public void putSymbol(String name, Node dn) {
        putSymbol(name, dn, false);
    }

    /**
     * Merge two conditional scopes into the current scope.
     *
     * @param ifScope
     * @param elseScope
     */
    public void mergeConditionalScopes(Map<String,Variable> ifScope, Map<String,Variable> elseScope) {
        var allSymbols = new HashMap<String,Variable>();

        // add symbols from if branch
        ifScope.forEach((name, variable) -> {
            if( variable.depth > currentDepth() )
                return;

            var other = elseScope.get(name);
            if( other != null )
                variable = variable.union(other);

            if( allSymbols.containsKey(name) )
                allSymbols.put(name, allSymbols.get(name).union(variable));
            else
                allSymbols.put(name, variable.shallowCopy());
        });

        // add remaining symbols from else branch
        elseScope.forEach((name, variable) -> {
            if( variable.depth > currentDepth() )
                return;

            if( !allSymbols.containsKey(name) )
                allSymbols.put(name, variable.shallowCopy());
        });

        // add merged symbols to current scope
        scopes.peek().putAll(allSymbols);
    }

    private int currentDepth() {
        return scopes.size();
    }

}


class Variable {

    public final int depth;

    public final Set<Node> preds;

    Variable(int depth, Set<Node> preds) {
        this.depth = depth;
        this.preds = preds;
    }

    public Variable shallowCopy() {
        return new Variable(depth, new HashSet<Node>(preds));
    }

    public Variable union(Variable other) {
        var allPreds = new HashSet<Node>(preds);
        allPreds.addAll(other.preds);
        return new Variable(depth, allPreds);
    }
}
