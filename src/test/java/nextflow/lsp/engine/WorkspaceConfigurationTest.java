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

import java.util.List;
import java.util.Map;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import nextflow.lsp.services.ErrorReportingMode;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.UNUSED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies how the workspace applies configuration changes: error
 * reporting mode, file excludes and invalid settings.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class WorkspaceConfigurationTest {

    static final String WITH_WARNING = "workflow { def x = 1 }\n";

    @Test
    void republishWithoutRebuildingWhenErrorReportingModeChanges() {
        var server = TestServer.start((o) -> o.settle = false);
        server.write("main.nf", WITH_WARNING);
        server.write("bad.nf", "workflow {\n");
        server.settle();
        var before = server.parses();
        assertThat(server.diagnostics("main.nf")).singleElement()
            .satisfies(d -> assertThat(d.getMessage()).isEqualTo(UNUSED))
            .satisfies(d -> assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Warning));
        assertThat(server.diagnostics("bad.nf")).isNotEmpty()
            .allMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);

        server.configure(Map.of("nextflow.errorReportingMode", "errors"));
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();
        assertThat(server.messages("bad.nf")).isNotEmpty();

        server.configure(Map.of("nextflow.errorReportingMode", "off"));
        server.settle();
        assertThat(server.messages("main.nf")).isEmpty();
        assertThat(server.messages("bad.nf")).isEmpty();

        server.configure(Map.of("nextflow.errorReportingMode", "warnings"));
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(UNUSED);
        assertThat(server.messages("bad.nf")).isNotEmpty();
        assertThat(server.parsesSince(before)).isEmpty();
    }

    @Test
    void doNotFlickerDiagnosticsOnConfigurationChange() {
        var server = TestServer.start((o) -> o.settle = false);
        server.write("main.nf", WITH_WARNING);
        server.write("nextflow.config", "foo.bar = 1\n");
        server.settle();
        server.client().resetDiagnostics();

        server.configure(Map.of(
            "nextflow.files.exclude", List.of(".git", "work", "other"),
            "nextflow.formatting.harshilAlignment", true));
        server.settle();
        server.configure(Map.of("nextflow.files.exclude", List.of(".git", "work")));
        server.settle();
        assertThat(server.client().getPublishLog()).isEmpty();
        assertThat(server.workspace().configuration().harshilAlignment()).as("harshilAlignment").isTrue();
    }

    @Test
    void applyExcludeChangesIncrementally() {
        var server = TestServer.start((o) -> o.settle = false);
        server.write("main.nf", WITH_WARNING);
        server.write("sub/x.nf", WITH_WARNING);
        server.write("tests/y.nf", WITH_WARNING);
        server.configure(Map.of("nextflow.files.exclude", List.of("tests")));
        server.settle();
        assertThat(server.messages("sub/x.nf")).containsExactly(UNUSED);
        assertThat(server.publishes("tests/y.nf")).isEmpty();

        server.configure(Map.of("nextflow.files.exclude", List.of("sub")));
        server.settle();
        assertThat(server.publishes("sub/x.nf")).satisfiesExactly(
            (first) -> assertThat(first).extracting(Diagnostic::getMessage).containsExactly(UNUSED),
            (last) -> assertThat(last).isEmpty());
        assertThat(server.messages("tests/y.nf")).containsExactly(UNUSED);
        assertThat(server.publishes("main.nf")).hasSize(1);
        assertThat(server.call(Need.FILE, "sub/x.nf", "empty", (context) -> "ran")).isEqualTo("empty");
    }

    @Test
    void ignoreTheResultOfASupersededWalk() {
        var io = new QueuedExecutor();
        var server = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withIoExecutor(io);
            o.initialized = false;
            o.settle = false;
        });
        server.write("main.nf", WITH_WARNING);
        server.write("tests/y.nf", WITH_WARNING);
        server.configure(Map.of("nextflow.files.exclude", List.of("tests")));
        server.initialized();
        server.runPending();
        server.configure(Map.of("nextflow.files.exclude", List.of(".git", "work")));
        server.runPending();
        assertThat(io.size()).isEqualTo(2);

        // the first walk, which excludes tests/, completes last
        io.runLast();
        server.runPending();
        server.settle(io);
        assertThat(server.messages("main.nf")).containsExactly(UNUSED);
        assertThat(server.messages("tests/y.nf")).containsExactly(UNUSED);
        assertThat(server.publishes("tests/y.nf")).hasSize(1);
    }

    @Test
    void ignoreInvalidSettingsButApplyTheOthers() {
        var server = TestServer.start();
        server.configure(Map.of(
            "nextflow.errorReportingMode", "bogus",
            "nextflow.completion.maxItems", "many",
            "nextflow.dag.direction", "LR"));
        server.settle();
        var configuration = server.workspace().configuration();
        assertThat(configuration.errorReportingMode()).isEqualTo(ErrorReportingMode.WARNINGS);
        assertThat(configuration.maxCompletionItems()).isEqualTo(100);
        assertThat(configuration.dagDirection()).isEqualTo("LR");
        assertThat(server.client().getLogMessages())
            .filteredOn(m -> m.getType() == MessageType.Warning)
            .extracting(m -> m.getMessage())
            .anyMatch(m -> m.contains("nextflow.errorReportingMode"))
            .anyMatch(m -> m.contains("nextflow.completion.maxItems"));
    }

    @Test
    void ignoreInvalidRegistryUrlButApplyTheOtherSettings() {
        var server = TestServer.start((o) -> {
            o.environment = new TestEnvironment().withRegistry(HttpPluginRegistryClient::new);
            o.settle = false;
        });
        server.write("main.nf", WITH_WARNING);
        server.settle();
        assertThat(server.messages("main.nf")).containsExactly(UNUSED);

        var registryUrl = server.workspace().configuration().pluginRegistryUrl();
        server.configure(Map.of(
            "nextflow.pluginRegistryUrl", "http://bad url/",
            "nextflow.errorReportingMode", "off"));
        server.settle();
        assertThat(server.workspace().configuration().pluginRegistryUrl()).isEqualTo(registryUrl);
        assertThat(server.messages("main.nf")).isEmpty();
        assertThat(server.client().getLogMessages())
            .filteredOn(m -> m.getType() == MessageType.Warning)
            .extracting(m -> m.getMessage())
            .anyMatch(m -> m.contains("nextflow.pluginRegistryUrl"));
    }

}
