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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import nextflow.lsp.ast.ASTUtils;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.WorkflowNode;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class WorkspacePreviewProvider {

    private ScriptAstCache ast;

    public WorkspacePreviewProvider(ScriptAstCache ast) {
        this.ast = ast;
    }

    public Map<String,Object> preview() {
        var result = ast.getUris().stream()
            .filter(uri -> !ast.hasSyntaxErrors(uri))
            .flatMap(uri -> definitions(uri))
            .toList();
        return Map.of("result", result);
    }

    private Stream<? extends Object> definitions(URI uri) {
        var processes = ast.getProcessNodes(uri).stream()
            .map(pn -> Map.of(
                "name", pn.getName(),
                "type", "process",
                "path", uri.getPath(),
                "line", pn.getLineNumber() - 1
            ));
        var workflows = ast.getWorkflowNodes(uri).stream()
            .map(wn -> Map.of(
                "name", wn.isEntry() ? "<entry>" : wn.getName(),
                "type", "workflow",
                "path", uri.getPath(),
                "line", wn.getLineNumber() - 1,
                "children", children(wn)
            ));
        return Stream.concat(processes, workflows);
    }

    private List<? extends Object> children(WorkflowNode node) {
        return new OutgoingCallsVisitor().apply(node).stream()
            .map(call -> ASTUtils.getMethodFromCallExpression(call, ast))
            .filter(mn -> mn instanceof ProcessNode || mn instanceof WorkflowNode)
            .distinct()
            .map(mn -> Map.of(
                "name", mn.getName(),
                "path", ast.getURI(mn).getPath()
            ))
            .toList();
    }

}
