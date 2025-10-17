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

import java.util.List;

import com.google.gson.JsonPrimitive;
import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.services.CallHierarchyProvider;
import nextflow.lsp.services.CodeLensProvider;
import nextflow.lsp.services.CompletionProvider;
import nextflow.lsp.services.DefinitionProvider;
import nextflow.lsp.services.FormattingProvider;
import nextflow.lsp.services.HoverProvider;
import nextflow.lsp.services.LanguageServerConfiguration;
import nextflow.lsp.services.LanguageService;
import nextflow.lsp.services.LinkProvider;
import nextflow.lsp.services.ReferenceProvider;
import nextflow.lsp.services.RenameProvider;
import nextflow.lsp.services.SemanticTokensProvider;
import nextflow.lsp.services.SymbolProvider;
import nextflow.script.formatter.FormattingOptions;

/**
 * Implementation of language services for Nextflow scripts.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptService extends LanguageService {

    private ScriptAstCache astCache;

    public ScriptService(String rootUri) {
        super(rootUri);
        astCache = new ScriptAstCache(rootUri);
    }

    @Override
    public boolean matchesFile(String uri) {
        return uri.endsWith(".nf");
    }

    @Override
    public void initialize(LanguageServerConfiguration configuration) {
        synchronized (this) {
            astCache.initialize(configuration);
        }
        super.initialize(configuration);
    }

    @Override
    protected ASTNodeCache getAstCache() {
        return astCache;
    }

    @Override
    protected CallHierarchyProvider getCallHierarchyProvider() {
        return new ScriptCallHierarchyProvider(astCache);
    }

    @Override
    protected CodeLensProvider getCodeLensProvider() {
        return new ScriptCodeLensProvider(astCache);
    }

    @Override
    protected CompletionProvider getCompletionProvider(int maxItems, boolean extended) {
        return new ScriptCompletionProvider(astCache, maxItems, extended);
    }

    @Override
    protected DefinitionProvider getDefinitionProvider() {
        return new ScriptDefinitionProvider(astCache);
    }

    @Override
    protected FormattingProvider getFormattingProvider() {
        return new ScriptFormattingProvider(astCache);
    }

    @Override
    protected HoverProvider getHoverProvider() {
        return new ScriptHoverProvider(astCache);
    }

    @Override
    protected LinkProvider getLinkProvider() {
        return new ScriptLinkProvider(astCache);
    }

    @Override
    protected ReferenceProvider getReferenceProvider() {
        return new ScriptReferenceProvider(astCache);
    }

    @Override
    protected RenameProvider getRenameProvider() {
        return new ScriptReferenceProvider(astCache);
    }

    @Override
    protected SemanticTokensProvider getSemanticTokensProvider() {
        return new ScriptSemanticTokensProvider(astCache);
    }

    @Override
    protected SymbolProvider getSymbolProvider() {
        return new ScriptSymbolProvider(astCache);
    }

    @Override
    public Object executeCommand(String command, List<Object> arguments, LanguageServerConfiguration configuration) {
        updateNow();
        if( "nextflow.server.previewDag".equals(command) && arguments.size() == 2 ) {
            var uri = getJsonString(arguments.get(0));
            var name = getJsonString(arguments.get(1));
            var provider = new ScriptCodeLensProvider(astCache);
            return provider.previewDag(uri, name, configuration.dagDirection(), configuration.dagVerbose());
        }
        if( "nextflow.server.previewWorkspace".equals(command) ) {
            var provider = new WorkspacePreviewProvider(astCache);
            return provider.preview();
        }
        if( "nextflow.server.convertPipelineToTyped".equals(command) ) {
            var provider = new ScriptCodeLensProvider(astCache);
            var options = formattingOptions(configuration);
            return provider.convertPipelineToTyped(options);
        }
        if( "nextflow.server.convertScriptToTyped".equals(command) ) {
            var uri = getJsonString(arguments.get(0));
            var provider = new ScriptCodeLensProvider(astCache);
            var options = formattingOptions(configuration);
            return provider.convertScriptToTyped(uri, options);
        }
        return null;
    }

    private String getJsonString(Object json) {
        return json instanceof JsonPrimitive jp ? jp.getAsString() : null;
    }

    private static FormattingOptions formattingOptions(LanguageServerConfiguration configuration) {
        return new FormattingOptions(
            4,
            true,
            configuration.harshilAlignment(),
            configuration.maheshForm(),
            configuration.sortDeclarations()
        );
    }

}
