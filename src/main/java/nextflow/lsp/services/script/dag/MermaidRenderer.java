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

import static org.codehaus.groovy.ast.tools.GeneralUtils.inSamePackage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.function.Function;

import org.eclipse.lsp4j.jsonrpc.messages.Tuple;

import groovy.lang.Tuple2;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class MermaidRenderer {

    private final boolean hideVariables;
    private final boolean uniqueNames;
    private Collection<Node> inputs = null;
    private Collection<Node> outputs = null;

    public MermaidRenderer(boolean hideVariables) {
        this.hideVariables = hideVariables;
        this.uniqueNames = false;
    }

    public String render(String name, Graph graph) {
        var isEntry = name == null;
        var lines = new ArrayList<String>();
        lines.add("flowchart TB");
        lines.add(String.format("subgraph %s", isEntry ? "\" \"" : name));
        
        // prepare inputs and outputs
        inputs = graph.inputs.values();
        var nodes = graph.nodes.values();
        outputs = graph.outputs.values();

        // Collapse graph
        graph.collapseGraph(isHidden(inputs, outputs), isHiddenIfDisconnected());

        // render inputs
        if( inputs.size() > 0 ) {
            lines.add(String.format("  subgraph %s", isEntry ? "params" : "take"));
            for( var dn : inputs ) {
                if( dn == null )
                    continue;
                lines.add("    " + renderNode(dn.id, dn.label, dn.type));
            }
            lines.add("  end");
        }

        var subgraphEdges = renderSubgraphs(graph.activeSubgraphs.peek(), graph, lines, 0);

        // render outputs
        if( outputs.size() > 0 ) {
            lines.add(String.format("  subgraph %s", isEntry ? "publish" : "emit"));
            for( var dn : outputs )
                lines.add("    " + renderNode(dn.id, dn.label, dn.type));
            lines.add("  end");
        }

        // render edges
        for( var dn : nodes )
            for( var dnPred : dn.preds )
                lines.add(String.format("    v%d --> v%d", dnPred.id, dn.id));

        for( var e : subgraphEdges ) {
            lines.add(String.format("    v%d --> s%d", e.getV2(), e.getV1()));
        }

        lines.add("end");

        return String.join("\n", lines);
    }

    // Write the subgraphs and fetch the list of subgraph edges
    private Set<Tuple2<Integer, Integer>> renderSubgraphs(Subgraph s, Graph g, ArrayList<String> lines, int depth) {

        if( depth > 0 ) {
            lines.add("  ".repeat(depth) + String.format("subgraph s%d[\" \"]", s.getId()));
        }
        for( var dn : s.getMembers() ) {
            if (g.nodes.containsKey(dn.id)) {

                var label = dn.label.replaceAll("\n", "\\\\\n").replaceAll("\"", "\\\\\"");

                lines.add("  ".repeat(depth + 1) + renderNode(dn.id, label, dn.type));
                if( dn.uri != null ) {
                    lines.add(String.format("    click v%d href \"%s\" _blank", dn.id, dn.uri.toString()));
                }

            }
        }
        // Get incoming edges
        Set<Tuple2<Integer, Integer>> incidentEdges = new HashSet<>();
        for( var p : s.getPreds() ) {
            incidentEdges.add(new Tuple2<Integer, Integer>(s.getId(), p.id));
        }
        for( var child : s.getChildren() ) {
            var moreEdges = renderSubgraphs(child, g, lines, depth + 1);
            incidentEdges.addAll(moreEdges);
        }
        if( depth > 0 ) {
            lines.add("  ".repeat(depth) + "end");
        }
        return incidentEdges;
    }

    /**
     * Only inputs, outputs, and processes/workflows are currently shown.
     *
     * @param dn
     * @param inputs
     * @param outputs
     */
    private Function<Node, Boolean> isHidden(Collection<Node> inputs, Collection<Node> outputs) {
        return (dn -> hideVariables && dn.type == Node.Type.NAME && !inputs.contains(dn) && !outputs.contains(dn));
    }

    private Function<Node, Boolean> isHiddenIfDisconnected() {
        return (dn -> hideVariables && dn.type == Node.Type.NULL);
    }

    private String renderNode(int id, String label, Node.Type type) {
        String name;
        if( uniqueNames ) {
            name = String.format("%s<%d>", label, id);
        } else {
            name = String.format("%s", label);
        }
        return switch (type) {
        case NAME -> String.format("v%d[\"%s\"]", id, name);
        case OPERATOR -> String.format("v%d([%s])", id, name);
        case CONDITIONAL -> String.format("v%d([conditional])", id);
        case NULL -> String.format("v%d([null])", id);
        };
    }

}
