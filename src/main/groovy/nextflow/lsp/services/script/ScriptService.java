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

import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.services.CallHierarchyProvider;
import nextflow.lsp.services.CompletionProvider;
import nextflow.lsp.services.DefinitionProvider;
import nextflow.lsp.services.FormattingProvider;
import nextflow.lsp.services.HoverProvider;
import nextflow.lsp.services.LanguageService;
import nextflow.lsp.services.LinkProvider;
import nextflow.lsp.services.ReferenceProvider;
import nextflow.lsp.services.RenameProvider;
import nextflow.lsp.services.SymbolProvider;

/**
 * Implementation of language services for Nextflow scripts.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptService extends LanguageService {

    private ScriptAstCache astCache = new ScriptAstCache();

    @Override
    public boolean matchesFile(String uri) {
        return uri.endsWith(".nf");
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
    protected CompletionProvider getCompletionProvider() {
        return new ScriptCompletionProvider(astCache);
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
    protected SymbolProvider getSymbolProvider() {
        return new ScriptSymbolProvider(astCache);
    }

}
