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

package nextflow.lsp

import nextflow.lsp.services.ErrorReportingMode
import nextflow.lsp.services.LanguageServerConfiguration
import nextflow.lsp.services.config.ConfigService
import nextflow.lsp.spec.PluginSpecCache
import org.eclipse.lsp4j.DiagnosticSeverity
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 * Tests for diagnostic publishing in the base LanguageService:
 * error-reporting-mode filtering, severity mapping, and clearDiagnostics().
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class DiagnosticsReportingTest extends Specification {

    // a config with an unrecognized option -> warning
    private static final String WARNING_SOURCE = "wokDir = 'work'\n"
    // a config with a syntax error -> error
    private static final String ERROR_SOURCE = 'process {\n'

    private ConfigService serviceWithMode(TestLanguageClient client, ErrorReportingMode mode) {
        def d = LanguageServerConfiguration.defaults()
        def configuration = new LanguageServerConfiguration(
            d.dagDirection(), d.dagVerbose(), mode, d.excludePatterns(),
            d.extendedCompletion(), d.harshilAlignment(), d.maheshForm(),
            d.maxCompletionItems(), d.pluginRegistryUrl(), d.sortDeclarations())
        def service = new ConfigService(workspaceRootUri())
        service.connect(client)
        service.initialize(configuration, new PluginSpecCache(configuration.pluginRegistryUrl()))
        // mirror TestUtils.getConfigService: open empty file to skip the workspace scan
        open(service, getUri('nextflow.config'), '')
        service.updateNow()
        return service
    }

    def 'OFF should suppress all diagnostics' () {
        given:
        def client = new TestLanguageClient()
        def service = serviceWithMode(client, ErrorReportingMode.OFF)
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, ERROR_SOURCE)
        service.updateNow()
        then:
        client.getDiagnostics(uri).isEmpty()
    }

    def 'ERRORS should report errors but suppress warnings' () {
        given:
        def client = new TestLanguageClient()
        def service = serviceWithMode(client, ErrorReportingMode.ERRORS)
        def uri = getUri('nextflow.config')

        when: 'a syntax error'
        open(service, uri, ERROR_SOURCE)
        service.updateNow()
        then:
        client.getDiagnostics(uri).any { it.getSeverity() == DiagnosticSeverity.Error }

        when: 'an unrecognized-option warning'
        open(service, uri, WARNING_SOURCE)
        service.updateNow()
        then:
        client.getDiagnostics(uri).isEmpty()
    }

    def 'WARNINGS should report both errors and warnings' () {
        given:
        def client = new TestLanguageClient()
        def service = serviceWithMode(client, ErrorReportingMode.WARNINGS)
        def uri = getUri('nextflow.config')

        when: 'an unrecognized-option warning'
        open(service, uri, WARNING_SOURCE)
        service.updateNow()
        then:
        client.getDiagnostics(uri).any { it.getSeverity() == DiagnosticSeverity.Warning }

        when: 'a syntax error'
        open(service, uri, ERROR_SOURCE)
        service.updateNow()
        then:
        client.getDiagnostics(uri).any { it.getSeverity() == DiagnosticSeverity.Error }
    }

    def 'every diagnostic should be sourced to nextflow' () {
        given:
        def client = new TestLanguageClient()
        def service = serviceWithMode(client, ErrorReportingMode.WARNINGS)
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, WARNING_SOURCE)
        service.updateNow()
        then:
        def diagnostics = client.getDiagnostics(uri)
        !diagnostics.isEmpty()
        diagnostics.every { it.getSource() == 'nextflow' }
    }

    def 'clearDiagnostics should publish empty diagnostics for known files' () {
        given:
        def client = new TestLanguageClient()
        def service = serviceWithMode(client, ErrorReportingMode.WARNINGS)
        def uri = getUri('nextflow.config')

        when:
        open(service, uri, ERROR_SOURCE)
        service.updateNow()
        then:
        !client.getDiagnostics(uri).isEmpty()

        when:
        service.clearDiagnostics()
        then:
        client.getDiagnostics(uri).isEmpty()
    }

}
