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
package nextflow.lsp.services.groovy;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import groovy.lang.GroovyClassLoader;
import nextflow.lsp.compiler.LanguageServerErrorCollector;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class GroovyAstCache {

    private CompilerConfiguration config;

    private GroovyClassLoader classLoader;

    private CompilationUnit compilationUnit;

    public GroovyAstCache() {
        this.config = createConfiguration();
        this.classLoader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), config, true);
    }

    protected CompilerConfiguration createConfiguration() {
        var config = new CompilerConfiguration();
        var optimizationOptions = config.getOptimizationOptions();
        optimizationOptions.put(CompilerConfiguration.GROOVYDOC, true);

        return config;
    }

    public void compile(Set<URI> uris) {
        // TODO: extend CompilationUnit with removeSources() ?
        compilationUnit = new CompilationUnit(config, null, classLoader);
        // compilation.setErrorCollector(createErrorCollector())
        for( var uri : uris ) {
            var sourceUnit = new SourceUnit(
                    new File(uri),
                    config,
                    classLoader,
                    createErrorCollector());
            compilationUnit.addSource(sourceUnit);
        }

        try {
            compilationUnit.compile(Phases.CANONICALIZATION);
        }
        catch( CompilationFailedException e ) {
            // ignore
        }
        catch( GroovyBugError e ) {
            System.err.println("Unexpected exception in language server when compiling Groovy: " + e.toString());
        }
        catch( Exception e ) {
            System.err.println("Unexpected exception in language server when compiling Groovy: " + e.toString());
        }

        var errors = compilationUnit.getErrorCollector().getErrors();
        if( errors != null ) {
            for( var error : errors ) {
                if( !(error instanceof SyntaxErrorMessage) )
                    continue;
                var sem = (SyntaxErrorMessage) error;
                var cause = sem.getCause();
                System.err.println("Groovy error: " + cause.toString() + ": " + cause.getMessage());
            }
        }
    }

    protected ErrorCollector createErrorCollector() {
        return new LanguageServerErrorCollector(config);
    }

    public List<ClassNode> getClassNodes() {
        if( compilationUnit == null )
            return Collections.emptyList();

        var result = new ArrayList<ClassNode>();
        compilationUnit.iterator().forEachRemaining((sourceUnit) -> {
            var moduleNode = sourceUnit.getAST();
            if( moduleNode == null )
                return;
            result.addAll(moduleNode.getClasses());
        });
        return result;
    }

}
