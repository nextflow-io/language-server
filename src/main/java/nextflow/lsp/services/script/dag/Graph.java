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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 * @author Erik Danielsson <danielsson.erik.0@gmail.com>
 */
class Graph {

    public final Map<String,Node> inputs = new HashMap<>();

    public final Map<Integer,Node> nodes = new HashMap<>();

    public final Map<String,Node> outputs = new HashMap<>();

    private Stack<Subgraph> subgraphs = new Stack<>();

    private int nextSubgraphId = 0;

    public Graph() {
        pushSubgraph();
    }

    public Subgraph peekSubgraph() {
        return subgraphs.peek();
    }

    public void pushSubgraph() {
        subgraphs.push(new Subgraph(nextSubgraphId, null));
        nextSubgraphId += 1;
    }

    public void pushSubgraph(Node dn) {
        subgraphs.push(new Subgraph(nextSubgraphId, dn));
        nextSubgraphId += 1;
    }

    public Subgraph popSubgraph() {
        var result = subgraphs.pop();
        subgraphs.peek().subgraphs.add(result);
        return result;
    }

    public Node addNode(String label, Node.Type type, URI uri, Set<Node> preds) {
        var id = nodes.size();
        var dn = new Node(id, label, type, uri, preds);
        nodes.put(id, dn);
        subgraphs.peek().nodes.add(dn);
        return dn;
    }
}


class Subgraph {

    public final int id;

    public final Node pred;

    public final List<Subgraph> subgraphs = new ArrayList<>();

    public final Set<Node> nodes = new HashSet<>();

    public Subgraph(int id, Node pred) {
        this.id = id;
        this.pred = pred;
    }
}


class Node {
    public enum Type {
        NAME,
        OPERATOR,
        CONTROL
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
