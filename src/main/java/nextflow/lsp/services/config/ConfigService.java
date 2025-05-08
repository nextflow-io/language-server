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
package nextflow.lsp.services.config;

import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.services.CompletionProvider;
import nextflow.lsp.services.FormattingProvider;
import nextflow.lsp.services.HoverProvider;
import nextflow.lsp.services.LanguageServerConfiguration;
import nextflow.lsp.services.LanguageService;
import nextflow.lsp.services.LinkProvider;
import nextflow.lsp.services.SemanticTokensProvider;

/**
 * Implementation of language services for Nextflow config files.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigService extends LanguageService {

    private ConfigAstCache astCache;

    public ConfigService(String rootUri) {
        super(rootUri);
        astCache = new ConfigAstCache();
    }

    @Override
    public boolean matchesFile(String uri) {
        return uri.endsWith(".config") && !uri.endsWith("nf-test.config");
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
    protected CompletionProvider getCompletionProvider(int maxItems, boolean extended) {
        return new ConfigCompletionProvider(astCache);
    }

    @Override
    protected FormattingProvider getFormattingProvider() {
        return new ConfigFormattingProvider(astCache);
    }

    @Override
    protected HoverProvider getHoverProvider() {
        return new ConfigHoverProvider(astCache);
    }

    @Override
    protected LinkProvider getLinkProvider() {
        return new ConfigLinkProvider(astCache);
    }

    @Override
    protected SemanticTokensProvider getSemanticTokensProvider() {
        return new ConfigSemanticTokensProvider(astCache);
    }

}
