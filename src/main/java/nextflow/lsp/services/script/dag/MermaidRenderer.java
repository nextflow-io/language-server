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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 * @author Erik Danielsson <danielsson.erik.0@gmail.com>
 */
public class MermaidRenderer {

    private static final boolean SHOW_VARIABLES = false;

    private StringBuilder builder;

    private int indent;

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
        append("flowchart TB");
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

        // render nodes
        var allSubgraphs = new ArrayList<Subgraph>();
        renderSubgraph(graph.peekSubgraph(), allSubgraphs);

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
        for( var dn : nodes ) {
            if( isHidden(dn, inputs, outputs) )
                continue;

            var preds = dn.preds;
            var visited = new HashSet<Node>();
            while( true ) {
                var done = preds.stream().allMatch(p -> !isHidden(p, inputs, outputs));
                if( done )
                    break;
                visited.addAll(preds);
                preds = preds.stream()
                    .flatMap(pred -> (
                        isHidden(pred, inputs, outputs)
                            ? pred.preds.stream().filter(p -> !visited.contains(p))
                            : Stream.of(pred)
                    ))
                    .collect(Collectors.toSet());
            }

            for( var dnPred : preds )
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
        allSubgraphs.add(subgraph);

        if( subgraph.id > 0 ) {
            append("subgraph s%d[\" \"]", subgraph.id);
            incIndent();
        }

        // render nodes
        for( var dn : subgraph.nodes ) {
            if( dn.type == Node.Type.NAME )
                continue;

            var label = dn.label
                .replaceAll("\n", "\\\\\n")
                .replaceAll("\"", "\\\\\"");

            append(renderNode(dn.id, label, dn.type));
            if( dn.uri != null )
                append("click v%d href \"%s\" _blank", dn.id, dn.uri.toString());
        }

        // render subgraphs
        for( var s : subgraph.subgraphs )
            renderSubgraph(s, allSubgraphs);

        if( subgraph.id > 0 ) {
            decIndent();
            append("end");
        }
    }

    /**
     * Only inputs, outputs, and processes/workflows are currently shown.
     *
     * @param dn
     * @param inputs
     * @param outputs
     */
    private boolean isHidden(Node dn, Collection<Node> inputs, Collection<Node> outputs) {
        return isHidden(dn) && !inputs.contains(dn) && !outputs.contains(dn);
    }

    private boolean isHidden(Node dn) {
        return !SHOW_VARIABLES && dn.type == Node.Type.NAME;
    }

    private static String renderNode(int id, String label, Node.Type type) {
        return switch( type ) {
            case NAME     -> String.format("v%d[\"%s\"]", id, label);
            case OPERATOR -> String.format("v%d([%s])", id, label);
            case CONTROL  -> String.format("v%d{ }", id);
        };
    }

}
