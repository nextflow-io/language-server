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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies the canonical form of document URIs.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class UrisTest {

    @Test
    void normalizeFileUris() {
        assertThat(Uris.canonical("file:///a/b/../c/./d.nf")).contains(URI.create("file:///a/c/d.nf"));
        assertThat(Uris.canonical("file:///a/%62.nf")).contains(URI.create("file:///a/b.nf"));
        assertThat(Uris.canonical("FILE:///a/b.nf")).contains(URI.create("file:///a/b.nf"));
    }

    @Test
    void rejectNonFileAndMalformedUris() {
        assertThat(Uris.canonical("untitled:Untitled-1")).isEmpty();
        assertThat(Uris.canonical("git:/a/b.nf?ref=HEAD")).isEmpty();
        assertThat(Uris.canonical("file:///a b.nf")).isEmpty();
        assertThat(Uris.canonical("file:///a/b.nf?query")).isEmpty();
        assertThat(Uris.canonical("relative/b.nf")).isEmpty();
        assertThat(Uris.canonical((String) null)).isEmpty();
    }

}
