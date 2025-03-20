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
package nextflow.lsp.services.script;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import groovy.lang.GroovyClassLoader;
import nextflow.script.control.LazyErrorCollector;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;

/**
 * Load Groovy classes from the `lib` directory.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class GroovyLibCache {

    private Map<URI,Entry> cache = new HashMap<>();

    List<ClassNode> load(String rootUri) {
        if( rootUri == null )
            return Collections.emptyList();

        // collect Groovy files in lib directory
        var libDir = Path.of(URI.create(rootUri)).resolve("lib");
        if( !Files.isDirectory(libDir) )
            return Collections.emptyList();

        Set<URI> uris;
        try {
            uris = Files.walk(libDir)
                .filter(path -> path.toString().endsWith(".groovy"))
                .map(path -> path.toUri())
                .collect(Collectors.toSet());
        }
        catch( IOException e ) {
            System.err.println("Failed to read Groovy source files in lib directory: " + e.toString());
            return Collections.emptyList();
        }

        if( uris.isEmpty() )
            return Collections.emptyList();

        // compile source files
        var cachedClasses = new ArrayList<ClassNode>();
        var config = new CompilerConfiguration();
        config.getOptimizationOptions().put(CompilerConfiguration.GROOVYDOC, true);
        var classLoader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), config, true);
        var compilationUnit = new CompilationUnit(config, null, classLoader);
        for( var uri : uris ) {
            var lastModified = getLastModified(uri);
            if( cache.containsKey(uri) ) {
                var entry = cache.get(uri);
                if( lastModified != null && lastModified.equals(entry.lastModified) ) {
                    if( entry.classes != null )
                        cachedClasses.addAll(entry.classes);
                    continue;
                }
            }

            System.err.println("compile " + uri.toString());
            var sourceUnit = new SourceUnit(
                    new File(uri),
                    config,
                    classLoader,
                    new LazyErrorCollector(config));
            compilationUnit.addSource(sourceUnit);
            cache.put(uri, new Entry(lastModified));
        }

        try {
            compilationUnit.compile(org.codehaus.groovy.control.Phases.CANONICALIZATION);
        }
        catch( CompilationFailedException e ) {
            // ignore
        }
        catch( GroovyBugError | Exception e ) {
            System.err.println("Failed to compile Groovy source files in lib directory -- " + e.toString());
        }

        // collect class nodes and report errors
        var result = new ArrayList<ClassNode>();
        result.addAll(cachedClasses);
        compilationUnit.iterator().forEachRemaining((sourceUnit) -> {
            var uri = sourceUnit.getSource().getURI();
            var errors = sourceUnit.getErrorCollector().getErrors();
            if( errors != null ) {
                for( var error : errors ) {
                    if( !(error instanceof SyntaxErrorMessage) )
                        continue;
                    var sem = (SyntaxErrorMessage) error;
                    var cause = sem.getCause();
                    System.err.println(String.format("Groovy syntax error in %s -- %s: %s", uri, cause, cause.getMessage()));
                }
            }

            var moduleNode = sourceUnit.getAST();
            if( moduleNode == null )
                return;
            var packageName = libDir
                .relativize(Path.of(uri).getParent())
                .toString()
                .replaceAll("/", ".");
            moduleNode.setPackageName(packageName);
            for( var cn : moduleNode.getClasses() ) {
                var className = packageName.isEmpty()
                    ? cn.getNameWithoutPackage()
                    : packageName + "." + cn.getNameWithoutPackage();
                cn.setName(className);
            }
            result.addAll(moduleNode.getClasses());

            var entry = cache.get(uri);
            entry.classes = moduleNode.getClasses();
        });
        return result;
    }

    private FileTime getLastModified(URI uri) {
        try {
            return Files.getLastModifiedTime(Path.of(uri));
        }
        catch( IOException e ) {
            System.err.println(String.format("Failed to get last modified time for %s -- %s", uri, e));
            return null;
        }
    }

    private static class Entry {
        FileTime lastModified;
        List<ClassNode> classes;

        Entry(FileTime lastModified) {
            this.lastModified = lastModified;
        }
    }

}
