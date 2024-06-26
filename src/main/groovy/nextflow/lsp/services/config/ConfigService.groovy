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
package nextflow.lsp.services.config

import groovy.lang.GroovyClassLoader
import groovy.transform.CompileStatic
import nextflow.config.v2.ConfigParserPluginFactory
import nextflow.lsp.ast.ASTNodeCache
import nextflow.lsp.compiler.Compiler
import nextflow.lsp.compiler.CompilerTransform
import nextflow.lsp.services.CompletionProvider
import nextflow.lsp.services.FormattingProvider
import nextflow.lsp.services.HoverProvider
import nextflow.lsp.services.LanguageService
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit

/**
 * Implementation of language services for Nextflow config files.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ConfigService extends LanguageService {

    private ConfigAstCache astCache = new ConfigAstCache(getCompiler())

    @Override
    boolean matchesFile(String uri) {
        uri.endsWith('.config') && !uri.endsWith('nf-test.config')
    }

    @Override
    protected ASTNodeCache getAstCache() {
        return astCache
    }

    protected Compiler getCompiler() {
        final config = createConfiguration()
        final classLoader = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true)
        final CompilerTransform transform = new CompilerTransform() {
            @Override
            void visit(SourceUnit sourceUnit) {
                new ConfigSchemaVisitor(sourceUnit).visit()
            }
        }
        return new Compiler(config, classLoader, List.of(transform))
    }

    protected CompilerConfiguration createConfiguration() {
        final config = new CompilerConfiguration()
        config.setPluginFactory(new ConfigParserPluginFactory())
        return config
    }

    @Override
    protected CompletionProvider getCompletionProvider() {
        new ConfigCompletionProvider(astCache)
    }

    @Override
    protected FormattingProvider getFormattingProvider() {
        new ConfigFormattingProvider(astCache)
    }

    @Override
    protected HoverProvider getHoverProvider() {
        new ConfigHoverProvider(astCache)
    }

}
