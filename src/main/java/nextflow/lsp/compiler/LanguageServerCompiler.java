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
package nextflow.lsp.compiler;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import groovy.lang.GroovyClassLoader;
import nextflow.lsp.file.FileCache;
import nextflow.script.control.Compiler;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.SourceUnit;

/**
 * Compiler that can load source files from the file cache
 * and defers errors to the language server.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class LanguageServerCompiler extends Compiler {

    public LanguageServerCompiler(CompilerConfiguration configuration, GroovyClassLoader classLoader) {
        super(configuration, classLoader);
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
    public SourceUnit getSource(URI uri, FileCache fileCache) {
        if( fileCache.isOpen(uri) ) {
            var contents = fileCache.getContents(uri);
            return new SourceUnit(
                    uri.toString(),
                    new StringReaderSourceWithURI(contents, uri, configuration()),
                    configuration(),
                    classLoader(),
                    newErrorCollector());
        }
        else if( Files.exists(Path.of(uri)) ) {
            return new SourceUnit(
                    new File(uri),
                    configuration(),
                    classLoader(),
                    newErrorCollector());
        }
        else
            return null;
    }

    private ErrorCollector newErrorCollector() {
        return new LanguageServerErrorCollector(configuration());
    }

    /**
     * Compile a source file enough to build the AST, and defer
     * any errors to be handled later.
     *
     * @param sourceUnit
     */
    @Override
    public void compile(SourceUnit sourceUnit) {
        try {
            super.compile(sourceUnit);
        }
        catch( GroovyBugError | Exception e ) {
            var uri = sourceUnit.getSource().getURI();
            System.err.println("Unexpected exception while compiling " + uri.getPath() + ": " + e.toString());
            e.printStackTrace();
        }
    }

}
