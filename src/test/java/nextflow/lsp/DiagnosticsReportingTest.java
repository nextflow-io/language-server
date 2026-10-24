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

import java.util.Map;

import nextflow.lsp.services.ErrorReportingMode;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for diagnostic publishing: error-reporting-mode filtering,
 * severity mapping, and clearing diagnostics.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class DiagnosticsReportingTest {

    // a config with an unrecognized option -> warning
    private static final String WARNING_SOURCE = "wokDir = 'work'\n";
    // a config with a syntax error -> error
    private static final String ERROR_SOURCE = "process {\n";

    private static TestServer serverWithMode(TestLanguageClient client, ErrorReportingMode mode) {
        return TestServer.start((o) -> {
            o.client = client;
            o.settings = Map.of("nextflow.errorReportingMode", mode.name());
        });
    }

    @Test
    void offSuppressesAllDiagnostics() {
        var client = new TestLanguageClient();
        var server = serverWithMode(client, ErrorReportingMode.OFF);
        var uri = getUri("nextflow.config");

        server.open("nextflow.config", ERROR_SOURCE);
        server.settle();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    @Test
    void errorsReportsErrorsButSuppressesWarnings() {
        var client = new TestLanguageClient();
        var server = serverWithMode(client, ErrorReportingMode.ERRORS);
        var uri = getUri("nextflow.config");

        // a syntax error
        server.open("nextflow.config", ERROR_SOURCE);
        server.settle();
        assertThat(client.getDiagnostics(uri)).anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);

        // an unrecognized-option warning
        server.open("nextflow.config", WARNING_SOURCE);
        server.settle();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    @Test
    void warningsReportsBothErrorsAndWarnings() {
        var client = new TestLanguageClient();
        var server = serverWithMode(client, ErrorReportingMode.WARNINGS);
        var uri = getUri("nextflow.config");

        // an unrecognized-option warning
        server.open("nextflow.config", WARNING_SOURCE);
        server.settle();
        assertThat(client.getDiagnostics(uri)).anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Warning);

        // a syntax error
        server.open("nextflow.config", ERROR_SOURCE);
        server.settle();
        assertThat(client.getDiagnostics(uri)).anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
    }

    @Test
    void everyDiagnosticIsSourcedToNextflow() {
        var client = new TestLanguageClient();
        var server = serverWithMode(client, ErrorReportingMode.WARNINGS);
        var uri = getUri("nextflow.config");

        server.open("nextflow.config", WARNING_SOURCE);
        server.settle();
        var diagnostics = client.getDiagnostics(uri);
        assertThat(diagnostics).isNotEmpty();
        assertThat(diagnostics).allMatch(d -> "nextflow".equals(d.getSource()));
    }

}
