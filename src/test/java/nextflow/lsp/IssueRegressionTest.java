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
package nextflow.lsp;

import java.util.ArrayList;
import java.util.List;

import nextflow.lsp.engine.QueuedExecutor;
import nextflow.lsp.engine.TestEnvironment;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression tests for issues of the old language
 * service core.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class IssueRegressionTest {

    static final String UNUSED = "Variable was declared but not used";

    /**
     * #175: a cross-file request sent right after didOpen, while the
     * workspace is still being indexed, waits for the index and gets
     * full results.
     */
    @Test
    void crossFileRequestAfterOpenGetsFullResults() {
        var io = new QueuedExecutor();
        var ended = new ArrayList<Boolean>();
        var client = new TestLanguageClient() {
            @Override
            public void notifyProgress(ProgressParams params) {
                super.notifyProgress(params);
                if( params.getValue().getLeft() instanceof WorkDoneProgressEnd )
                    ended.add(true);
            }
        };
        try( var server = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withIoExecutor(io);
            o.client = client;
            o.progress = true;
            o.initialized = false;
        }) ) {
            var module = "workflow GREET {}\n";
            server.write("modules/greet/main.nf", module);
            server.write("main.nf", """
                include { GREET } from './modules/greet/main.nf'

                workflow {
                    GREET()
                }
                """);
            server.server().initialized(new InitializedParams());
            server.runPending();

            server.open("modules/greet/main.nf", module);
            var endedAtAnswer = new ArrayList<Boolean>();
            var params = new ReferenceParams(server.document("modules/greet/main.nf"), new Position(0, 10), new ReferenceContext(true));
            var references = server.send(s -> s.references(params));
            references.whenComplete((result, error) -> endedAtAnswer.add(!ended.isEmpty()));
            server.runPending();
            assertThat(references).isNotDone();

            server.settle(io);
            // the definition, plus the include and the call in main.nf
            assertThat(references.join())
                .extracting(Location::getUri)
                .containsExactlyInAnyOrder(server.uri("modules/greet/main.nf"), server.uri("main.nf"), server.uri("main.nf"));
            assertThat(endedAtAnswer).containsExactly(true);
        }
    }

    /**
     * #108: editing an included module doesn't duplicate the warnings
     * of the files that include it.
     */
    @Test
    void noDuplicateWarningsAfterEditingAnIncludedModule() {
        try( var server = TestServer.start() ) {
            server.open("mod.nf", "workflow FOO {}\n");
            server.open("main.nf", """
                include { FOO } from './mod.nf'

                workflow {
                    def x = 1
                    FOO()
                }
                """);
            server.settle();
            assertThat(messages(server.diagnostics("main.nf"))).containsExactly(UNUSED);

            for( int i = 0; i < 3; i++ ) {
                server.change("mod.nf", "workflow FOO {}\n" + "// edit " + i + "\n");
                server.settle();
                assertThat(messages(server.diagnostics("main.nf"))).containsExactly(UNUSED);
            }
        }
    }

    static List<String> messages(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(Diagnostic::getMessage).toList();
    }

}
