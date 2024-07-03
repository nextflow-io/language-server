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
package nextflow.lsp.compiler

import java.nio.file.Files
import java.nio.file.Path

import groovy.lang.GroovyClassLoader
import groovy.transform.CompileStatic
import nextflow.lsp.file.FileCache
import nextflow.lsp.util.Logger
import org.antlr.v4.runtime.RecognitionException
import org.apache.groovy.parser.antlr4.GroovySyntaxError
import org.codehaus.groovy.GroovyBugError
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.SourceUnit

/**
 * Compile source files and defer any errors to the
 * language server.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class Compiler {

    private static Logger log = Logger.instance

    private CompilerConfiguration config

    private GroovyClassLoader classLoader

    private List<CompilerTransform> transforms

    Compiler(CompilerConfiguration config, GroovyClassLoader classLoader, List<CompilerTransform> transforms) {
        this.config = config
        this.classLoader = classLoader
        this.transforms = transforms
    }

    /**
     * Compile a set of source files.
     *
     * @param uris
     * @param fileCache
     */
    Map<URI, SourceUnit> compile(Set<URI> uris, FileCache fileCache) {
        final stream = uris.parallelStream().map((uri) -> {
            final sourceUnit = getSourceUnit(uri, fileCache)
            if( sourceUnit )
                compile(sourceUnit)
            return new Tuple2<>(uri, sourceUnit)
        })

        final Map<URI, SourceUnit> result = [:]
        for( final tuple : stream ) {
            final uri = tuple.v1
            final sourceUnit = tuple.v2
            result[uri] = sourceUnit
        }
        return result
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
            final contents = fileCache.getContents(uri)
            return new SourceUnit(
                    uri.toString(),
                    new StringReaderSourceWithURI(contents, uri, config),
                    config,
                    classLoader,
                    newErrorCollector())
        }
        else if( Files.exists(Path.of(uri)) ) {
            return new SourceUnit(
                    new File(uri),
                    config,
                    classLoader,
                    newErrorCollector())
        }
        else
            return null
    }

    protected ErrorCollector newErrorCollector() {
        new LanguageServerErrorCollector(config)
    }

    /**
     * Compile a source file enough to build the AST, and defer
     * any errors to be handled later.
     *
     * @param sourceUnit
     */
    protected void compile(SourceUnit sourceUnit) {
        try {
            sourceUnit.parse()
            sourceUnit.buildAST()
            for( final transform : transforms )
                transform.visit(sourceUnit)
        }
        catch( RecognitionException e ) {
        }
        catch( GroovySyntaxError e ) {
        }
        catch( GroovyBugError e ) {
            log.error 'Unexpected exception while compiling source files'
            e.printStackTrace(System.err)
        }
        catch( Exception e ) {
            log.error 'Unexpected exception while compiling source files'
            e.printStackTrace(System.err)
        }
    }

}
