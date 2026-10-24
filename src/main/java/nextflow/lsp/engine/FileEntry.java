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
package nextflow.lsp.engine;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import nextflow.lsp.spec.PluginRef;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.syntax.SyntaxException;

/**
 * The build state of a managed file in a project.
 *
 * An input event bumps the input version. A build records the
 * input version it observed, a new build stamp, and the stamps of
 * every dependency and external input it read, so that freshness
 * can be verified later without pushing staleness to dependents.
 *
 * Engine-confined. Mutated only by {@link Project} and {@link Builder}.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class FileEntry {

    public enum Role {
        /** Part of the project's file set (discovered, or open). */
        WORKSPACE,
        /** Loaded only because a managed file depends on it. */
        DEPENDENCY
    }

    static final long UNBUILT = -1;

    // the stamp of a dependency that has no entry
    static final long MISSING = -1;

    final URI uri;

    final Path path;

    final FileKind kind;

    Role role;

    long inputVersion;

    long builtInputVersion = UNBUILT;

    long stamp;

    // every path that an include of the last build could resolve to,
    // with the stamp of its entry at the time, or MISSING
    Map<URI, Long> depStamps = Map.of();

    Map<ExtKey, Long> extKeys = Map.of();

    // set when an input other than the text changed since the last build
    boolean otherInputChanged;

    SourceUnit unit;

    // the text of the last build, which the source unit also holds
    String builtText;

    Map<ASTNode, ASTNode> parents = Map.of();

    List<ASTNode> nodes = List.of();

    List<SyntaxException> errors = List.of();

    List<WarningMessage> warnings = List.of();

    Integer builtFromVersion;

    Throwable internalError;

    List<PluginRef> pluginRefs = List.of();

    // the number of times the file was parsed, for tests
    int parses;

    FileEntry(URI uri, Path path, FileKind kind, Role role) {
        this.uri = uri;
        this.path = path;
        this.kind = kind;
        this.role = role;
    }

    public URI uri() {
        return uri;
    }

    public FileKind kind() {
        return kind;
    }

    /**
     * Whether the entry has been built at least once.
     */
    public boolean isBuilt() {
        return builtInputVersion != UNBUILT;
    }

    /**
     * The source unit of the last build, or null if the entry has
     * not been built.
     */
    public SourceUnit unit() {
        return unit;
    }

    /**
     * The errors of the last build. Immutable.
     */
    public List<SyntaxException> errors() {
        return errors;
    }

    /**
     * The warnings of the last build. Immutable.
     */
    public List<WarningMessage> warnings() {
        return warnings;
    }

    /**
     * The indexed AST nodes of the last build. Immutable.
     */
    public List<ASTNode> nodes() {
        return nodes;
    }

    /**
     * The parent of each indexed AST node. Immutable.
     */
    public Map<ASTNode, ASTNode> parents() {
        return parents;
    }

    /**
     * The exception that interrupted the last build of this file, if any.
     */
    public Throwable internalError() {
        return internalError;
    }

    @Override
    public String toString() {
        return "FileEntry[" + uri + "]";
    }

}
