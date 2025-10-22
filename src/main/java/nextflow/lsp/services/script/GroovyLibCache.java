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
import java.nio.file.FileVisitOption;
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
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;

/**
 * Load Groovy classes from the `lib` directory.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class GroovyLibCache {

    private Path libDir;

    private Map<URI,Entry> cache = new HashMap<>();

    public GroovyLibCache(Path libDir) {
        this.libDir = libDir;
    }

    public List<ClassNode> refresh() {
        // collect Groovy files in lib directory
        if( libDir == null || !Files.isDirectory(libDir) )
            return Collections.emptyList();

        var uris = groovyFiles(libDir);

        if( uris.isEmpty() )
            return Collections.emptyList();

        // compile source files
        var result = new ArrayList<ClassNode>();
        var compilationUnit = compile(uris, result);

        // collect compiled class nodes
        collectClasses(compilationUnit, result);

        return result;
    }

    private static Set<URI> groovyFiles(Path libDir) {
        try {
            return Files.walk(libDir, FileVisitOption.FOLLOW_LINKS)
                .filter(path -> path.toString().endsWith(".groovy"))
                .map(path -> path.toUri())
                .collect(Collectors.toSet());
        }
        catch( IOException e ) {
            System.err.println(String.format("Failed to read Groovy source files in lib directory: %s -- %s", libDir, e));
            return Collections.emptySet();
        }
    }

    private CompilationUnit compile(Set<URI> uris, List<ClassNode> classes) {
        // create compilation unit
        var config = new CompilerConfiguration();
        config.getOptimizationOptions().put(CompilerConfiguration.GROOVYDOC, true);
        var classLoader = new GroovyClassLoader();
        var compilationUnit = new CompilationUnit(config, null, classLoader);

        // create source units (or restore from cache)
        for( var uri : uris ) {
            var lastModified = lastModified(uri);
            if( cache.containsKey(uri) ) {
                var entry = cache.get(uri);
                if( lastModified != null && lastModified.equals(entry.lastModified) ) {
                    if( entry.classes != null )
                        classes.addAll(entry.classes);
                    continue;
                }
            }

            System.err.println("compile " + uri.getPath());
            var sourceUnit = new SourceUnit(
                    new File(uri),
                    config,
                    classLoader,
                    new LazyErrorCollector(config));
            compilationUnit.addSource(sourceUnit);
            cache.put(uri, new Entry(lastModified));
        }

        // compile source files
        try {
            compilationUnit.compile(Phases.CANONICALIZATION);
        }
        catch( CompilationFailedException e ) {
            // ignore
        }
        catch( GroovyBugError | Exception e ) {
            System.err.println(String.format("Failed to compile Groovy source files in lib directory -- %s", e));
        }

        return compilationUnit;
    }

    private static FileTime lastModified(URI uri) {
        try {
            return Files.getLastModifiedTime(Path.of(uri));
        }
        catch( IOException e ) {
            System.err.println(String.format("Failed to get last modified time for %s -- %s", uri.getPath(), e));
            return null;
        }
    }

    private void collectClasses(CompilationUnit compilationUnit, List<ClassNode> classes) {
        compilationUnit.iterator().forEachRemaining((sourceUnit) -> {
            var uri = sourceUnit.getSource().getURI();

            // report syntax errors
            var errors = sourceUnit.getErrorCollector().getErrors();
            if( errors != null ) {
                for( var error : errors ) {
                    if( !(error instanceof SyntaxErrorMessage) )
                        continue;
                    var sem = (SyntaxErrorMessage) error;
                    var cause = sem.getCause();
                    System.err.println(String.format("Groovy syntax error in %s -- %s: %s", uri.getPath(), cause, cause.getMessage()));
                }
            }

            // collect compiled classes
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
            classes.addAll(moduleNode.getClasses());

            // update cache
            var entry = cache.get(uri);
            entry.classes = moduleNode.getClasses();
        });
    }

    private static class Entry {
        FileTime lastModified;
        List<ClassNode> classes;

        Entry(FileTime lastModified) {
            this.lastModified = lastModified;
        }
    }

}
