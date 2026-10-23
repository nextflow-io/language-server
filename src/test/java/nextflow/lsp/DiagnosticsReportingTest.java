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

import nextflow.lsp.services.ErrorReportingMode;
import nextflow.lsp.services.LanguageServerConfiguration;
import nextflow.lsp.services.config.ConfigService;
import nextflow.lsp.spec.PluginSpecCache;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for diagnostic publishing in the base LanguageService:
 * error-reporting-mode filtering, severity mapping, and clearDiagnostics().
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class DiagnosticsReportingTest {

    // a config with an unrecognized option -> warning
    private static final String WARNING_SOURCE = "wokDir = 'work'\n";
    // a config with a syntax error -> error
    private static final String ERROR_SOURCE = "process {\n";

    private static ConfigService serviceWithMode(TestLanguageClient client, ErrorReportingMode mode) {
        var d = LanguageServerConfiguration.defaults();
        var configuration = new LanguageServerConfiguration(
            d.dagDirection(), d.dagVerbose(), mode, d.excludePatterns(),
            d.extendedCompletion(), d.harshilAlignment(), d.maheshForm(),
            d.maxCompletionItems(), d.pluginRegistryUrl(), d.sortDeclarations());
        var service = new ConfigService(workspaceRootUri());
        service.connect(client);
        service.initialize(configuration, new PluginSpecCache(TestPluginRegistry.url()));
        // mirror TestUtils.getConfigService: open empty file to skip the workspace scan
        open(service, getUri("nextflow.config"), "");
        service.updateNow();
        return service;
    }

    @Test
    void offSuppressesAllDiagnostics() {
        var client = new TestLanguageClient();
        var service = serviceWithMode(client, ErrorReportingMode.OFF);
        var uri = getUri("nextflow.config");

        open(service, uri, ERROR_SOURCE);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    @Test
    void errorsReportsErrorsButSuppressesWarnings() {
        var client = new TestLanguageClient();
        var service = serviceWithMode(client, ErrorReportingMode.ERRORS);
        var uri = getUri("nextflow.config");

        // a syntax error
        open(service, uri, ERROR_SOURCE);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);

        // an unrecognized-option warning
        open(service, uri, WARNING_SOURCE);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

    @Test
    void warningsReportsBothErrorsAndWarnings() {
        var client = new TestLanguageClient();
        var service = serviceWithMode(client, ErrorReportingMode.WARNINGS);
        var uri = getUri("nextflow.config");

        // an unrecognized-option warning
        open(service, uri, WARNING_SOURCE);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Warning);

        // a syntax error
        open(service, uri, ERROR_SOURCE);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
    }

    @Test
    void everyDiagnosticIsSourcedToNextflow() {
        var client = new TestLanguageClient();
        var service = serviceWithMode(client, ErrorReportingMode.WARNINGS);
        var uri = getUri("nextflow.config");

        open(service, uri, WARNING_SOURCE);
        service.updateNow();
        var diagnostics = client.getDiagnostics(uri);
        assertThat(diagnostics).isNotEmpty();
        assertThat(diagnostics).allMatch(d -> "nextflow".equals(d.getSource()));
    }

    @Test
    void clearDiagnosticsPublishesEmptyDiagnosticsForKnownFiles() {
        var client = new TestLanguageClient();
        var service = serviceWithMode(client, ErrorReportingMode.WARNINGS);
        var uri = getUri("nextflow.config");

        open(service, uri, ERROR_SOURCE);
        service.updateNow();
        assertThat(client.getDiagnostics(uri)).isNotEmpty();

        service.clearDiagnostics();
        assertThat(client.getDiagnostics(uri)).isEmpty();
    }

}
