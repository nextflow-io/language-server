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
import java.util.Set;

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
 * every file that its includes could resolve to, so that freshness
 * can be verified later without pushing staleness to dependents.
 * Other inputs (`lib/`, parameter schemas and plugin specs) bump
 * the entries that read them.
 *
 * Engine-confined. Mutated only by {@link Project}, {@link Builder}
 * and {@link Workspace} (which sets the role).
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class FileEntry {

    enum Role {
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

    // the plugins declared by a config file
    List<PluginRef> pluginRefs = List.of();

    // the plugins whose specs the last build looked up
    Set<PluginRef> pluginLookups = Set.of();

    // the number of times the file was parsed, for tests
    int parses;

    FileEntry(URI uri, Path path, FileKind kind, Role role) {
        this.uri = uri;
        this.path = path;
        this.kind = kind;
        this.role = role;
    }

    /**
     * Whether the entry has been built at least once.
     */
    boolean isBuilt() {
        return builtInputVersion != UNBUILT;
    }

    @Override
    public String toString() {
        return "FileEntry[" + uri + "]";
    }

}
