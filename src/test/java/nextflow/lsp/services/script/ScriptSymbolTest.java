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

    static Map<String,SymbolKind> getDocumentSymbols(ScriptService service, String uri) {
        var symbols = service.documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri)));
        var result = new HashMap<String,SymbolKind>();
        for( var symbol : symbols )
            result.put(symbol.getRight().getName(), symbol.getRight().getKind());
        return result;
    }

    static List<String> getWorkspaceSymbolNames(ScriptService service, String query) {
        return service
            .symbol(new WorkspaceSymbolParams(query))
            .stream()
            .map(WorkspaceSymbol::getName)
            .toList();
    }

    @Test
    void provideDocumentSymbolForEachDefinitionKind() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        open(service, uri, """
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
        service.updateNow();
        var symbols = getDocumentSymbols(service, uri);

        assertThat(symbols.get("record Sample")).isEqualTo(SymbolKind.Struct);
        assertThat(symbols.get("enum Color")).isEqualTo(SymbolKind.Enum);
        assertThat(symbols.get("function greet")).isEqualTo(SymbolKind.Function);
        assertThat(symbols.get("process FOO")).isEqualTo(SymbolKind.Function);
        assertThat(symbols.get("workflow BAR")).isEqualTo(SymbolKind.Function);
        assertThat(symbols.get("workflow <entry>")).isEqualTo(SymbolKind.Function);
    }

    @Test
    void noDocumentSymbolsForEmptyFile() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        open(service, uri, "");
        service.updateNow();

        assertThat(getDocumentSymbols(service, uri)).isEmpty();
    }

    @Test
    void findWorkspaceSymbolsByCaseInsensitiveSubstring() {
        var service = getScriptService();
        var mainUri = getUri("main.nf");
        var moduleUri = getUri("module.nf");

        open(service, moduleUri, """
            process ALIGN {
                script:
                \"""
                echo align
                \"""
            }
            """);
        open(service, mainUri, """
            workflow ALIGN_READS {
            }

            workflow {
            }
            """);
        service.updateNow();

        // a query matches symbol names across files, case-insensitively
        var names = getWorkspaceSymbolNames(service, "align");
        assertThat(names).containsExactlyInAnyOrder("process ALIGN", "workflow ALIGN_READS");

        // a non-matching query returns nothing
        assertThat(getWorkspaceSymbolNames(service, "zzz")).isEmpty();

        // an empty query matches every definition
        assertThat(getWorkspaceSymbolNames(service, "")).hasSize(3);
    }

}
