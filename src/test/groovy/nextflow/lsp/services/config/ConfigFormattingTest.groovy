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

package nextflow.lsp.services.config

import java.nio.file.Files
import java.nio.file.Path

import nextflow.lsp.TestLanguageClient
import nextflow.lsp.services.LanguageServerConfiguration
import nextflow.script.formatter.FormattingOptions
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentItem
import spock.lang.Specification

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigFormattingTest extends Specification {

    ConfigService newConfigService() {
        def workspaceRoot = Path.of(System.getProperty('user.dir')).resolve('build/test_workspace/')
        if( !Files.exists(workspaceRoot) )
            workspaceRoot.toFile().mkdirs()

        def service = new ConfigService(workspaceRoot.toUri().toString())
        def configuration = LanguageServerConfiguration.defaults()
        service.connect(new TestLanguageClient())
        service.initialize(configuration)
        return service
    }

    Path configPath() {
        return Path.of(System.getProperty('user.dir')).resolve('build/test_workspace/nextflow.config')
    }

    void openFile(ConfigService service, Path filePath, String contents) {
        def uri = filePath.toUri()
        def textDocumentItem = new TextDocumentItem(uri.toString(), 'nextflow-config', 1, contents)
        service.didOpen(new DidOpenTextDocumentParams(textDocumentItem))
    }

    String openAndFormat(ConfigService service, Path filePath, String contents) {
        openFile(service, filePath, contents)
        def textEdits = service.formatting(filePath.toUri(), new FormattingOptions(4, true))
        return textEdits.first().getNewText()
    }

    def 'should format a config file' () {
        given:
        def service = newConfigService()

        when:
        def filePath = configPath()
        def contents = '''\
            process.cpus = 2 ; process.memory = 8.GB
            '''.stripIndent()
        then:
        openAndFormat(service, filePath, contents) == '''\
            process.cpus = 2
            process.memory = 8.GB
            '''.stripIndent()

        when:
        contents = '''\
            process.cpus = 2
            process.memory = 8.GB
            '''.stripIndent()
        then:
        openAndFormat(service, filePath, contents) == '''\
            process.cpus = 2
            process.memory = 8.GB
            '''.stripIndent()
    }

    def 'should preserve all comments when formatting a config file' () {
        given:
        def service = newConfigService()
        def filePath = configPath()

        expect:
        // trailing comments, dangling comments at the end of a block and at
        // the end of the file are all preserved
        openAndFormat(service, filePath, '''\
            process {
                cpus = 2 // trailing comment
                // comment after the last option
            }

            // comment at the end of the file
            '''.stripIndent()
        ) == '''\
            process {
                cpus = 2 // trailing comment
                // comment after the last option
            }

            // comment at the end of the file
            '''.stripIndent()
    }

    def 'should not format config regions excluded with fmt directives' () {
        given:
        def service = newConfigService()
        def filePath = configPath()

        expect:
        openAndFormat(service, filePath, '''\
            process.cpus = 2

            // fmt: off
            env.FOO    = 'one'
            env.BARBAZ = 'two'
            // fmt: on
            '''.stripIndent()
        ) == '''\
            process.cpus = 2

            // fmt: off
            env.FOO    = 'one'
            env.BARBAZ = 'two'
            // fmt: on
            '''.stripIndent()
    }

    def 'should produce identical output when formatting a cached config AST twice' () {
        given:
        def service = newConfigService()
        def filePath = configPath()
        def contents = '''\
            // top comment
            process {
                cpus = 2 // trailing comment
                // dangling comment
            }
            '''.stripIndent()
        // the file must exist on disk so that the deferred workspace scan
        // does not evict it from the AST cache between formatting calls
        Files.writeString(filePath, contents)

        when:
        openFile(service, filePath, contents)
        def edits = (1..3).collect {
            service.formatting(filePath.toUri(), new FormattingOptions(4, true))
        }

        then:
        edits.every { it.first().getNewText() == contents }

        cleanup:
        Files.deleteIfExists(filePath)
    }

}
