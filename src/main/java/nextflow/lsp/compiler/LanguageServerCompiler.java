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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import groovy.lang.GroovyClassLoader;
import nextflow.config.control.StringReaderSourceWithURI;
import nextflow.config.parser.ConfigAstBuilder;
import nextflow.config.parser.ConfigParserPluginFactory;
import nextflow.script.control.Compiler;
import nextflow.script.control.LazyErrorCollector;
import nextflow.script.parser.ScriptAstBuilder;
import nextflow.script.parser.ScriptParserPluginFactory;
import org.antlr.v4.runtime.RecognitionException;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.WarningMessage;

/**
 * Compiler that parses source files from the given text or from
 * disk, and defers errors to the language server.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class LanguageServerCompiler extends Compiler {

    public LanguageServerCompiler(CompilerConfiguration configuration, GroovyClassLoader classLoader) {
        super(configuration, classLoader);
    }

    /**
     * Create a compiler for Nextflow scripts. If a project directory
     * is given, its `lib` directory is added to the classpath.
     *
     * @param projectDir
     */
    public static LanguageServerCompiler forScripts(Path projectDir) {
        var config = new CompilerConfiguration();
        config.setPluginFactory(new ScriptParserPluginFactory());
        config.setWarningLevel(WarningMessage.POSSIBLE_ERRORS);

        var optimizationOptions = config.getOptimizationOptions();
        optimizationOptions.put(CompilerConfiguration.GROOVYDOC, true);
        optimizationOptions.put(ScriptAstBuilder.COMMENTS_OPTION, true);

        var classLoader = new GroovyClassLoader();
        if( projectDir != null )
            addLibToClasspath(classLoader, projectDir.resolve("lib"));
        return new LanguageServerCompiler(config, classLoader);
    }

    /**
     * Create a compiler for Nextflow config files.
     */
    public static LanguageServerCompiler forConfigs() {
        var config = new CompilerConfiguration();
        config.setPluginFactory(new ConfigParserPluginFactory());
        config.setWarningLevel(WarningMessage.POSSIBLE_ERRORS);
        config.getOptimizationOptions().put(ConfigAstBuilder.COMMENTS_OPTION, true);
        return new LanguageServerCompiler(config, new GroovyClassLoader());
    }

    /**
     * Add the `lib` directory and any JARs within it to the classpath,
     * the same way that Nextflow does. Groovy source files are compiled
     * on demand when a script refers to them.
     *
     * Source files are resolved against the lib directory on every lookup
     * rather than through the classpath, so that files (or the lib directory
     * itself) can be added while the language server is running. New JARs
     * require a new compiler.
     *
     * @param classLoader
     * @param libDir
     */
    private static void addLibToClasspath(GroovyClassLoader classLoader, Path libDir) {
        classLoader.setResourceLoader((name) -> {
            var source = libDir.resolve(name.replace('.', '/') + ".groovy");
            return Files.exists(source) ? source.toUri().toURL() : null;
        });
        if( !Files.isDirectory(libDir) )
            return;
        try( var files = Files.list(libDir) ) {
            files
                .filter(path -> path.toString().endsWith(".jar"))
                .sorted()
                .forEach(path -> classLoader.addClasspath(path.toString()));
        }
        catch( IOException e ) {
            System.err.println(String.format("Failed to read JAR files in lib directory: %s -- %s", libDir, e));
        }
    }

    /**
     * Prepare a source file from the given text.
     *
     * @param uri
     * @param text
     */
    public SourceUnit createSourceUnit(URI uri, String text) {
        return new SourceUnit(
                uri.toString(),
                new StringReaderSourceWithURI(text, uri, configuration()),
                configuration(),
                classLoader(),
                createErrorCollector());
    }

    @Override
    protected ErrorCollector createErrorCollector() {
        return new LazyErrorCollector(configuration());
    }

    /**
     * Parse a source file and build the AST. Syntax errors are added
     * to the error collector, while any other exception propagates.
     *
     * Can be called concurrently for different source units.
     *
     * @param sourceUnit
     */
    public void parse(SourceUnit sourceUnit) {
        try {
            sourceUnit.parse();
            sourceUnit.buildAST();
        }
        catch( RecognitionException | CompilationFailedException e ) {
            // syntax errors are in the error collector
        }
    }

}
