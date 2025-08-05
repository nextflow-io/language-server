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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 * @author Erik Danielsson <danielsson.erik.0@gmail.com>
 */
public class MermaidRenderer {

    private static final List<String> VALID_DIRECTIONS = List.of("LR", "TB", "TD");

    private final String direction;

    private final boolean verbose;

    private StringBuilder builder;

    private int indent;

    public MermaidRenderer(String direction, boolean verbose) {
        this.direction = VALID_DIRECTIONS.contains(direction) ? direction : "TB";
        this.verbose = verbose;
    }

    private void reset() {
        builder = new StringBuilder();
        indent = 0;
    }

    private void append(String format, Object... args) {
        builder.append("  ".repeat(indent));
        builder.append(String.format(format, args));
        builder.append('\n');
    }

    private void incIndent() {
        indent++;
    }

    private void decIndent() {
        indent--;
    }

    public String render(String name, Graph graph) {
        // prepare inputs and outputs
        var isEntry = name == null;
        var inputs = graph.inputs.values();
        var nodes = graph.nodes.values();
        var outputs = graph.outputs.values();

        // render graph
        reset();
        append("flowchart %s", direction);
        incIndent();
        append("subgraph %s", isEntry ? "\" \"" : name);
        incIndent();

        // render inputs
        if( inputs.size() > 0 ) {
            append("subgraph %s", isEntry ? "params" : "take");
            incIndent();
            for( var dn : inputs ) {
                if( dn == null )
                    continue;
                append(renderNode(dn.id, dn.label, dn.type));
            }
            decIndent();
            append("end");
        }

        // render nodes and subgraphs
        var root = graph.peekSubgraph();

        root.nodes.stream()
            .filter(n -> !inputs.contains(n))
            .filter(n -> !outputs.contains(n))
            .forEach(this::renderNode);

        var allSubgraphs = new ArrayList<Subgraph>();
        for( var s : root.subgraphs )
            renderSubgraph(s, allSubgraphs);

        // render outputs
        if( outputs.size() > 0 ) {
            append("subgraph %s", isEntry ? "publish" : "emit");
            incIndent();
            for( var dn : outputs )
                append(renderNode(dn.id, dn.label, dn.type));
            decIndent();
            append("end");
        }

        // render edges
        var visited = new HashMap<Node,Set<Node>>();

        for( var dn : nodes ) {
            if( isHidden(dn) )
                continue;

            for( var dnPred : visiblePreds(dn, visited) )
                append("v%d --> v%d", dnPred.id, dn.id);
        }

        // render subgraph edges
        for( var subgraph : allSubgraphs ) {
            if( subgraph.pred != null )
                append("v%d --> s%d", subgraph.pred.id, subgraph.id);
        }

        decIndent();
        append("end");

        return builder.toString();
    }

    /**
     * Render a subgraph and collect all child subgraphs.
     *
     * @param subgraph
     * @param allSubgraphs
     */
    private void renderSubgraph(Subgraph subgraph, List<Subgraph> allSubgraphs) {
        if( isHidden(subgraph) )
            return;

        allSubgraphs.add(subgraph);

        append("subgraph s%d[\" \"]", subgraph.id);
        incIndent();

        for( var dn : subgraph.nodes )
            renderNode(dn);

        for( var s : subgraph.subgraphs )
            renderSubgraph(s, allSubgraphs);

        decIndent();
        append("end");
    }

    /**
     * Render a node.
     *
     * @param dn
     */
    private void renderNode(Node dn) {
        if( isHidden(dn) )
            return;

        var label = dn.label
            .replaceAll("\n", "\\\\\n")
            .replaceAll("\"", "\\\\\"");

        append(renderNode(dn.id, label, dn.type));
        if( dn.uri != null )
            append("click v%d href \"%s\" _blank", dn.id, dn.uri.toString());
    }

    private static String renderNode(int id, String label, Node.Type type) {
        return switch( type ) {
            case NAME     -> String.format("v%d[\"%s\"]", id, label);
            case OPERATOR -> String.format("v%d([%s])", id, label);
            case CONTROL  -> String.format("v%d{ }", id);
        };
    }

    /**
     * Get the set of visible predecessors for a node.
     *
     * @param dn
     * @param visited
     */
    private Set<Node> visiblePreds(Node dn, Map<Node,Set<Node>> visited) {
        if( visited.containsKey(dn) )
            return visited.get(dn);

        var result = dn.preds.stream()
            .flatMap(pred -> (
                isHidden(pred)
                    ? visiblePreds(pred, visited).stream()
                    : Stream.of(pred)
            ))
            .collect(Collectors.toSet());
        visited.put(dn, result);
        return result;
    }

    /**
     * When verbose mode is disabled, all nodes marked as verbose are hidden.
     * Otherwise, only control nodes marked as verbose are hidden (because they
     * are disconnected).
     *
     * @param dn
     */
    private boolean isHidden(Node dn) {
        if( verbose )
            return dn.verbose && dn.type == Node.Type.CONTROL;
        else
            return dn.verbose;
    }

    /**
     * When verbose mode is disabled, subgraphs with no visible nodes
     * are hidden. Otherwise, only subgraphs with no nodes are hidden
     * (because they are disconnected).
     *
     * @param dn
     */
    private boolean isHidden(Subgraph s) {
        if( verbose )
            return s.nodes.isEmpty();
        else
            return s.isVerbose();
    }

}
