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

import java.util.List;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.DocumentLinkParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies document links for script includes.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ScriptLinkTest {

    static List<DocumentLink> getLinks(TestServer server, String uri) {
        return server.call(s -> s.documentLink(new DocumentLinkParams(new TextDocumentIdentifier(uri))));
    }

    @Test
    void resolveIncludeOfNfModule() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        server.open("main.nf", """
            include { HELLO } from './module.nf'

            workflow {
                HELLO()
            }
            """);
        var links = getLinks(server, uri);

        assertThat(links).hasSize(1);
        assertThat(links.get(0).getTarget()).isEqualTo(getUri("module.nf"));
    }

    @Test
    void appendNfExtensionWhenIncludeOmitsIt() {
        var server = TestServer.start();
        var uri = getUri("main.nf");

        server.open("main.nf", """
            include { HELLO } from './module'

            workflow {
                HELLO()
            }
            """);
        var links = getLinks(server, uri);

        assertThat(links).hasSize(1);
        assertThat(links.get(0).getTarget()).isEqualTo(getUri("module.nf"));
    }

    @Test
    void noLinkForPluginInclude() {
        // a module directory with the same name would otherwise resolve as a remote module
        writeWorkspaceFile("modules/plugin/nf-hello/main.nf", "");
        var server = TestServer.start();
        var uri = getUri("main.nf");

        server.open("main.nf", """
            include { sayHello } from 'plugin/nf-hello'

            workflow {
                sayHello()
            }
            """);

        assertThat(getLinks(server, uri)).isEmpty();
    }

}
