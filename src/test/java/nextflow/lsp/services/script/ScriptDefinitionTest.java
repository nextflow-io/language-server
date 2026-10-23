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

import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptDefinitionTest {

    static Location getDefinition(ScriptService service, String uri, Position position) {
        var locations = service
            .definition(new DefinitionParams(new TextDocumentIdentifier(uri), position))
            .getLeft();
        return locations.size() > 0 ? locations.get(0) : null;
    }

    @Test
    void getDefinitionOfWorkflowInSameFile() {
        var service = getScriptService();
        var uri = getUri("main.nf");

        var contents = """
            workflow HELLO {
            }

            workflow {
                HELLO()
            }
            """;
        open(service, uri, contents);
        service.updateNow();
        var location = getDefinition(service, uri, new Position(4, 4));

        assertThat(location).isNotNull();
        assertThat(location.getUri()).isEqualTo(uri);
        assertThat(location.getRange().getStart()).isEqualTo(new Position(0, 0));
        assertThat(location.getRange().getEnd()).isEqualTo(new Position(1, 1));
    }

    @Test
    void getDefinitionOfWorkflowInDifferentFile() {
        var service = getScriptService();
        var mainUri = getUri("main.nf");
        var moduleUri = getUri("module.nf");

        open(service, moduleUri, """
            workflow HELLO {
                take:
                target

                main:
                println "Hello, ${target}!"
            }
            """);
        open(service, mainUri, """
            include { HELLO } from './module.nf'

            workflow {
                HELLO('World')
            }
            """);
        service.updateNow();
        var location = getDefinition(service, mainUri, new Position(3, 4));

        assertThat(location).isNotNull();
        assertThat(location.getUri()).isEqualTo(moduleUri);
        assertThat(location.getRange().getStart()).isEqualTo(new Position(0, 0));
        assertThat(location.getRange().getEnd()).isEqualTo(new Position(6, 1));
    }

}
