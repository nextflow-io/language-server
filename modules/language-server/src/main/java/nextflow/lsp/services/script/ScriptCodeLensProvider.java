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
package nextflow.lsp.services.script;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import nextflow.lsp.services.CodeLensProvider;
import nextflow.lsp.services.script.dag.DataflowVisitor;
import nextflow.lsp.services.script.dag.MermaidRenderer;
import nextflow.lsp.util.Logger;
import nextflow.lsp.util.LanguageServerUtils;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptCodeLensProvider implements CodeLensProvider {

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;

    public ScriptCodeLensProvider(ScriptAstCache ast) {
        this.ast = ast;
    }

    @Override
    public List<CodeLens> codeLens(TextDocumentIdentifier textDocument) {
        if( ast == null ) {
            log.error("ast cache is empty while providing code lens");
            return Collections.emptyList();
        }

        var uri = URI.create(textDocument.getUri());
        if( !ast.hasAST(uri) || ast.hasErrors(uri) )
            return Collections.emptyList();

        var result = new ArrayList<CodeLens>();
        for( var wn : ast.getWorkflowNodes(uri) ) {
            var range = LanguageServerUtils.astNodeToRange(wn);
            if( range == null )
                continue;
            var name = wn.isEntry() ? JsonNull.INSTANCE : new JsonPrimitive(wn.getName());
            var arguments = List.of(new JsonPrimitive(uri.toString()), (Object) name);
            var command = new Command("Preview DAG", "nextflow.previewDag", arguments);
            result.add(new CodeLens(range, command, null));
        }

        return result;
    }

    public String previewDag(String documentUri, String name) {
        var uri = URI.create(documentUri);
        if( !ast.hasAST(uri) || ast.hasErrors(uri) )
            return null;

        var sourceUnit = ast.getSourceUnit(uri);
        return ast.getWorkflowNodes(uri).stream()
            .filter(wn -> wn.isEntry() ? name == null : wn.getName().equals(name))
            .findFirst()
            .map((wn) -> {
                var visitor = new DataflowVisitor(sourceUnit, ast);
                visitor.visit();

                var graph = visitor.getGraph(wn.isEntry() ? "<entry>" : wn.getName());
                var result = new MermaidRenderer().render(wn.getName(), graph);
                log.debug(result);
                return result;
            })
            .orElse(null);
    }

}
