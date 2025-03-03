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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class MermaidRenderer {

    public String render(String name, Graph graph) {
        var isEntry = name == null;
        var lines = new ArrayList<String>();
        lines.add("flowchart TB");
        lines.add(String.format("    subgraph %s", isEntry ? "\" \"" : name));

        // prepare inputs and outputs
        var inputs = graph.inputs.values();
        var nodes = graph.nodes.values();
        var outputs = graph.outputs.values();

        // render inputs
        if( inputs.size() > 0 ) {
            lines.add(String.format("    subgraph %s", isEntry ? "params" : "take"));
            for( var dn : inputs ) {
                if( dn == null )
                    continue;
                lines.add("    " + renderNode(dn.id, dn.label, dn.type));
            }
            lines.add("    end");
        }

        // render nodes
        for( var dn : nodes ) {
            if( dn.type == Node.Type.NAME )
                continue;

            var label = dn.label
                .replaceAll("\n", "\\\\\n")
                .replaceAll("\"", "\\\\\"");

            lines.add("    " + renderNode(dn.id, label, dn.type));

            if( dn.uri != null )
                lines.add(String.format("    click v%d href \"%s\" _blank", dn.id, dn.uri.toString()));
        }

        // render outputs
        if( outputs.size() > 0 ) {
            lines.add(String.format("    subgraph %s", isEntry ? "publish" : "emit"));
            for( var dn : outputs )
                lines.add("    " + renderNode(dn.id, dn.label, dn.type));
            lines.add("    end");
        }

        // render edges
        for( var dn : nodes ) {
            if( isHidden(dn, inputs, outputs) )
                continue;

            var preds = dn.preds;
            while( true ) {
                var done = preds.stream().allMatch(p -> !isHidden(p, inputs, outputs));
                if( done )
                    break;
                preds = preds.stream()
                    .flatMap(p ->
                        isHidden(p, inputs, outputs)
                            ? p.preds.stream()
                            : Stream.of(p)
                    )
                    .collect(Collectors.toSet());
            }

            for( var dnPred : preds )
                lines.add(String.format("    v%d --> v%d", dnPred.id, dn.id));
        }

        lines.add("    end");

        return String.join("\n", lines);
    }

    /**
     * Only inputs, outputs, and processes/workflows are currently shown.
     *
     * @param dn
     * @param inputs
     * @param outputs
     */
    private static boolean isHidden(Node dn, Collection<Node> inputs, Collection<Node> outputs) {
        return dn.type == Node.Type.NAME && !inputs.contains(dn) && !outputs.contains(dn);
    }

    private static String renderNode(int id, String label, Node.Type type) {
        return switch( type ) {
            case NAME     -> String.format("v%d[\"%s\"]", id, label);
            case OPERATOR -> String.format("v%d([%s])", id, label);
        };
    }

}
