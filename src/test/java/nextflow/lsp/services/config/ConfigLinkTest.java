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

package nextflow.lsp.services.config;

import java.util.List;

import nextflow.lsp.TestWorkspace;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.DocumentLinkParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ConfigLinkTest {

    static List<DocumentLink> getLinks(ConfigService service, String uri) {
        return service.documentLink(new DocumentLinkParams(new TextDocumentIdentifier(uri)));
    }

    @Test
    void resolveRelativeIncludeAgainstParentDirectory() {
        var service = getConfigService();
        var uri = getUri("nextflow.config");

        open(service, uri, """
            includeConfig 'conf/base.config'
            """);
        service.updateNow();
        var links = getLinks(service, uri);

        assertThat(links).hasSize(1);
        assertThat(links.get(0).getTarget()).isEqualTo(getUri("conf/base.config"));
    }

    @Test
    void passThroughAbsoluteIncludeUriUnchanged() {
        var service = getConfigService();
        var uri = getUri("nextflow.config");

        open(service, uri, """
            includeConfig 'https://example.com/shared.config'
            """);
        service.updateNow();
        var links = getLinks(service, uri);

        assertThat(links).hasSize(1);
        assertThat(links.get(0).getTarget()).isEqualTo("https://example.com/shared.config");
    }

    @Test
    void noLinksForConfigWithoutIncludes() {
        var service = getConfigService();
        var uri = getUri("nextflow.config");

        open(service, uri, """
            process.cpus = 2
            """);
        service.updateNow();

        assertThat(getLinks(service, uri)).isEmpty();
    }

}
