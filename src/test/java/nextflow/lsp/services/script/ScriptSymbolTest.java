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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ScriptSymbolTest {

    static Map<String,SymbolKind> getDocumentSymbols(TestServer server, String uri) {
        var symbols = server.call(s -> s.documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri))));
        var result = new HashMap<String,SymbolKind>();
        for( var symbol : symbols )
            result.put(symbol.getRight().getName(), symbol.getRight().getKind());
        return result;
    }

    static List<String> getWorkspaceSymbolNames(TestServer server, String query) {
        return server
            .call(s -> s.symbol(new WorkspaceSymbolParams(query)))
            .getRight()
            .stream()
            .map(WorkspaceSymbol::getName)
            .toList();
    }

    @Test
    void provideDocumentSymbolForEachDefinitionKind() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        server.open("main.nf", """
            record Sample {
                id: String
            }

            enum Color {
                RED, GREEN, BLUE
            }

            def greet(name) {
                return "Hello, ${name}!"
            }

            process FOO {
                script:
                \"""
                echo foo
                \"""
            }

            workflow BAR {
            }

            workflow {
                BAR()
            }
            """);
        var symbols = getDocumentSymbols(server, uri);

        assertThat(symbols.get("record Sample")).isEqualTo(SymbolKind.Struct);
        assertThat(symbols.get("enum Color")).isEqualTo(SymbolKind.Enum);
        assertThat(symbols.get("function greet")).isEqualTo(SymbolKind.Function);
        assertThat(symbols.get("process FOO")).isEqualTo(SymbolKind.Function);
        assertThat(symbols.get("workflow BAR")).isEqualTo(SymbolKind.Function);
        assertThat(symbols.get("workflow <entry>")).isEqualTo(SymbolKind.Function);
    }

    @Test
    void noDocumentSymbolsForEmptyFile() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        server.open("main.nf", "");

        assertThat(getDocumentSymbols(server, uri)).isEmpty();
    }

    @Test
    void findWorkspaceSymbolsByCaseInsensitiveSubstring() {
        var server = TestServer.start();

        server.open("module.nf", """
            process ALIGN {
                script:
                \"""
                echo align
                \"""
            }
            """);
        server.open("main.nf", """
            workflow ALIGN_READS {
            }

            workflow {
            }
            """);

        // a query matches symbol names across files, case-insensitively
        var names = getWorkspaceSymbolNames(server, "align");
        assertThat(names).containsExactlyInAnyOrder("process ALIGN", "workflow ALIGN_READS");

        // a non-matching query returns nothing
        assertThat(getWorkspaceSymbolNames(server, "zzz")).isEmpty();

        // an empty query matches every definition
        assertThat(getWorkspaceSymbolNames(server, "")).hasSize(3);
    }

    @Test
    void listSymbolsOfNestedFoldersOnce() {
        var server = TestServer.start((o) -> {
            o.folders("", "sub");
            o.settle = false;
        });
        server.write("sub/a.nf", "workflow A {}\n");
        server.write("sub/b.nf", "workflow B {}\n");
        server.write("main.nf", """
            include { A } from './sub/a.nf'
            workflow {
                A()
            }
            """);
        server.settle();
        assertThat(getWorkspaceSymbolNames(server, "")).containsExactlyInAnyOrder("workflow A", "workflow B", "workflow <entry>");
    }

}
