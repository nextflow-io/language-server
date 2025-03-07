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

import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import groovy.lang.GroovyClassLoader;
import nextflow.config.control.ConfigResolveVisitor;
import nextflow.config.control.ResolveIncludeVisitor;
import nextflow.config.parser.ConfigParserPluginFactory;
import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.compiler.LanguageServerCompiler;
import nextflow.lsp.compiler.LanguageServerErrorCollector;
import nextflow.script.control.PhaseAware;
import nextflow.script.control.Phases;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.WarningMessage;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigAstCache extends ASTNodeCache {

    public ConfigAstCache() {
        super(newCompiler());
    }

    private static LanguageServerCompiler newCompiler() {
        var config = createConfiguration();
        var classLoader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), config, true);
        return new LanguageServerCompiler(config, classLoader);
    }

    private static CompilerConfiguration createConfiguration() {
        var config = new CompilerConfiguration();
        config.setPluginFactory(new ConfigParserPluginFactory());
        config.setWarningLevel(WarningMessage.POSSIBLE_ERRORS);
        return config;
    }

    @Override
    protected Set<URI> analyze(Set<URI> uris) {
        // phase 2: include checking
        var changedUris = new HashSet<>(uris);

        for( var sourceUnit : getSourceUnits() ) {
            var visitor = new ResolveIncludeVisitor(sourceUnit, compiler(), uris);
            visitor.visit();

            var uri = sourceUnit.getSource().getURI();
            if( visitor.isChanged() ) {
                var errorCollector = (LanguageServerErrorCollector) sourceUnit.getErrorCollector();
                errorCollector.updatePhase(Phases.INCLUDE_RESOLUTION, visitor.getErrors());
                changedUris.add(uri);
            }
        }

        for( var uri : changedUris ) {
            var sourceUnit = getSourceUnit(uri);
            if( sourceUnit == null )
                continue;
            // phase 3: name checking
            new ConfigResolveVisitor(sourceUnit, compiler().compilationUnit()).visit();
            new ConfigSchemaVisitor(sourceUnit).visit();
            if( sourceUnit.getErrorCollector().hasErrors() )
                continue;
            // phase 4: type checking
            // TODO
        }

        return changedUris;
    }

    @Override
    protected Map<ASTNode, ASTNode> visitParents(SourceUnit sourceUnit) {
        var visitor = new ConfigAstParentVisitor(sourceUnit);
        visitor.visit();
        return visitor.getParents();
    }

    /**
     * Check whether a source file has any errors.
     *
     * @param uri
     */
    public boolean hasSyntaxErrors(URI uri) {
        return getErrors(uri).stream()
            .filter(error -> error instanceof PhaseAware pa ? pa.getPhase() == Phases.SYNTAX : true)
            .findFirst()
            .isPresent();
    }

}
