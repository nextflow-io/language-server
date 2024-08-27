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
package nextflow.lsp.services.script

import groovy.lang.GroovyClassLoader
import groovy.transform.CompileStatic
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.compiler.Compiler
import nextflow.lsp.compiler.CompilerTransform
import nextflow.lsp.services.CallHierarchyProvider
import nextflow.lsp.services.CompletionProvider
import nextflow.lsp.services.DefinitionProvider
import nextflow.lsp.services.FormattingProvider
import nextflow.lsp.services.HoverProvider
import nextflow.lsp.services.LanguageService
import nextflow.lsp.services.LinkProvider
import nextflow.lsp.services.ReferenceProvider
import nextflow.lsp.services.SymbolProvider
import nextflow.script.v2.ScriptParserPluginFactory
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.ImportCustomizer

/**
 * Implementation of language services for Nextflow scripts.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ScriptService extends LanguageService {

    private ScriptAstCache astCache = new ScriptAstCache(getCompiler())

    @Override
    boolean matchesFile(String uri) {
        uri.endsWith('.nf')
    }

    @Override
    protected ASTNodeCache getAstCache() {
        return astCache
    }

    protected Compiler getCompiler() {
        final config = createConfiguration()
        final classLoader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), config, true)
        final CompilerTransform transform = new CompilerTransform() {
            @Override
            void visit(SourceUnit sourceUnit) {
                new ResolveVisitor(sourceUnit, config, classLoader).visit()
            }
        }
        return new Compiler(config, classLoader, List.of(transform))
    }

    protected CompilerConfiguration createConfiguration() {
        final config = new CompilerConfiguration()
        config.setPluginFactory(new ScriptParserPluginFactory())

        final optimizationOptions = config.getOptimizationOptions()
        optimizationOptions.put(CompilerConfiguration.GROOVYDOC, true)

        return config
    }

    @Override
    protected CallHierarchyProvider getCallHierarchyProvider() {
        new ScriptCallHierarchyProvider(astCache)
    }

    @Override
    protected CompletionProvider getCompletionProvider() {
        new ScriptCompletionProvider(astCache)
    }

    @Override
    protected DefinitionProvider getDefinitionProvider() {
        new ScriptDefinitionProvider(astCache)
    }

    @Override
    protected FormattingProvider getFormattingProvider() {
        new ScriptFormattingProvider(astCache)
    }

    @Override
    protected HoverProvider getHoverProvider() {
        new ScriptHoverProvider(astCache)
    }

    @Override
    protected LinkProvider getLinkProvider() {
        new ScriptLinkProvider(astCache)
    }

    @Override
    protected ReferenceProvider getReferenceProvider() {
        new ScriptReferenceProvider(astCache)
    }

    @Override
    protected SymbolProvider getSymbolProvider() {
        new ScriptSymbolProvider(astCache)
    }

}
