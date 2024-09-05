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
package nextflow.lsp.compiler;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import groovy.lang.GroovyClassLoader;
import nextflow.lsp.file.FileCache;
import nextflow.lsp.util.Logger;
import org.antlr.v4.runtime.RecognitionException;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.SourceUnit;

/**
 * Compile source files and defer any errors to the
 * language server.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class Compiler {

    private static Logger log = Logger.getInstance();

    private CompilerConfiguration configuration;

    private GroovyClassLoader classLoader;

    public Compiler(CompilerConfiguration configuration, GroovyClassLoader classLoader) {
        this.configuration = configuration;
        this.classLoader = classLoader;
    }

    /**
     * Compile a set of source files.
     *
     * @param uris
     * @param fileCache
     */
    public Map<URI, SourceUnit> compile(Set<URI> uris, FileCache fileCache) {
        return uris.parallelStream()
            .map((uri) -> {
                var sourceUnit = getSourceUnit(uri, fileCache);
                if( sourceUnit != null )
                    compile(sourceUnit);
                return new AbstractMap.SimpleEntry<>(uri, sourceUnit);
            })
            .filter(entry -> entry.getValue() != null)
            .sequential()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue
            ));
    }

    /**
     * Prepare a source file from the open cache, or the filesystem
     * if it is not open.
     *
     * If the file is not open and doesn't exist in the filesystem,
     * it has been renamed. Return null so that the file is removed
     * from any downstream caches.
     *
     * @param uri
     */
    protected SourceUnit getSourceUnit(URI uri, FileCache fileCache) {
        if( fileCache.isOpen(uri) ) {
            var contents = fileCache.getContents(uri);
            return new SourceUnit(
                    uri.toString(),
                    new StringReaderSourceWithURI(contents, uri, configuration),
                    configuration,
                    classLoader,
                    newErrorCollector());
        }
        else if( Files.exists(Path.of(uri)) ) {
            return new SourceUnit(
                    new File(uri),
                    configuration,
                    classLoader,
                    newErrorCollector());
        }
        else
            return null;
    }

    protected ErrorCollector newErrorCollector() {
        return new LanguageServerErrorCollector(configuration);
    }

    /**
     * Compile a source file enough to build the AST, and defer
     * any errors to be handled later.
     *
     * @param sourceUnit
     */
    protected void compile(SourceUnit sourceUnit) {
        try {
            sourceUnit.parse();
            sourceUnit.buildAST();
        }
        catch( RecognitionException e ) {
        }
        catch( CompilationFailedException e ) {
        }
        catch( GroovyBugError | Exception e ) {
            log.error("Unexpected exception while compiling source files: " + e.toString());
        }
    }

}
