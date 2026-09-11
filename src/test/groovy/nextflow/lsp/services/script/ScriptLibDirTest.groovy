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

package nextflow.lsp.services.script

import java.nio.file.Files
import java.nio.file.Path

import nextflow.lsp.TestLanguageClient
import nextflow.lsp.services.LanguageServerConfiguration
import nextflow.lsp.spec.PluginSpecCache
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*

/**
 * Tests for resolving Groovy classes in the `lib` directory.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ScriptLibDirTest extends Specification {

    static final String GREETER = '''\
        class Greeter {
            static String greet(String name) {
                return "Hello, ${name}!"
            }
        }
        '''

    static final String WIDGET = '''\
        package acme

        class Widget {
            String name
        }
        '''

    Path libDir = Path.of(URI.create(workspaceRootUri())).resolve('lib')

    def cleanup() {
        libDir.toFile().deleteDir()
    }

    void writeLib(String path, String contents) {
        def file = libDir.resolve(path)
        Files.createDirectories(file.getParent())
        Files.writeString(file, contents.stripIndent())
    }

    def 'should resolve a class in the lib directory' () {
        given:
        writeLib('Greeter.groovy', GREETER)
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            workflow {
                println(Greeter.greet('world'))
            }
            ''')
        service.updateNow()
        then:
        client.getDiagnostics(uri).isEmpty()
    }

    def 'should resolve a class in a lib subdirectory by fully-qualified name' () {
        given:
        writeLib('acme/Widget.groovy', WIDGET)
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            workflow {
                println(new acme.Widget(name: 'sprocket').name)
            }
            ''')
        service.updateNow()
        then:
        client.getDiagnostics(uri).isEmpty()
    }

    def 'should not resolve a packaged class by simple name' () {
        given:
        writeLib('acme/Widget.groovy', WIDGET)
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            workflow {
                println(new Widget(name: 'sprocket').name)
            }
            ''')
        service.updateNow()
        def diagnostics = client.getDiagnostics(uri)
        then:
        diagnostics.size() == 1
        diagnostics.first().message.contains('Widget')
    }

    def 'should resolve a lib class used as a type annotation' () {
        given:
        writeLib('Greeter.groovy', GREETER)
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            def describe(Greeter greeter) {
                return greeter.toString()
            }

            workflow {
                println(describe(null))
            }
            ''')
        service.updateNow()
        then:
        client.getDiagnostics(uri).isEmpty()
    }

    def 'should report an error for a class that is not in the lib directory' () {
        given:
        writeLib('Greeter.groovy', GREETER)
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            workflow {
                println(Gruter.greet('world'))
            }
            ''')
        service.updateNow()
        def diagnostics = client.getDiagnostics(uri)
        then:
        diagnostics.size() == 1
        diagnostics.first().message.contains('Gruter')
    }

    def 'should report an error when there is no lib directory' () {
        given:
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            workflow {
                println(Greeter.greet('world'))
            }
            ''')
        service.updateNow()
        def diagnostics = client.getDiagnostics(uri)
        then:
        diagnostics.size() == 1
        diagnostics.first().message.contains('Greeter')
    }

    def 'should resolve a lib class when the lib directory is created after startup' () {
        given:
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')

        when:
        writeLib('Greeter.groovy', GREETER)
        open(service, uri, '''\
            workflow {
                println(Greeter.greet('world'))
            }
            ''')
        service.updateNow()
        then:
        client.getDiagnostics(uri).isEmpty()
    }

    def 'should pick up changes to a lib class without restarting' () {
        given:
        writeLib('Greeter.groovy', GREETER)
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')
        def contents = '''\
            workflow {
                println(Greeter.greet('world'))
            }
            '''

        when:
        open(service, uri, contents)
        service.updateNow()
        then:
        client.getDiagnostics(uri).isEmpty()

        when:
        writeLib('Greeter.groovy', GREETER.replace('class Greeter', 'class Salutation'))
        open(service, uri, contents)
        service.updateNow()
        def diagnostics = client.getDiagnostics(uri)
        then:
        diagnostics.size() == 1
        diagnostics.first().message.contains('Greeter')
    }

    def 'should report diagnostics when a lib class has a syntax error' () {
        given:
        writeLib('Greeter.groovy', 'class Greeter {')
        def client = new TestLanguageClient()
        def service = getScriptService(client)
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            workflow {
                println(Greeter.greet('world'))
                foo()
            }
            ''')
        service.updateNow()
        def diagnostics = client.getDiagnostics(uri)
        then:
        diagnostics.find { it.message.contains('foo') }
    }

    def 'should not fail when there is no workspace root' () {
        given:
        def client = new TestLanguageClient()
        def configuration = LanguageServerConfiguration.defaults()
        def service = new ScriptService(null)
        service.connect(client)
        service.initialize(configuration, new PluginSpecCache(configuration.pluginRegistryUrl()))
        def uri = getUri('main.nf')

        when:
        open(service, uri, '''\
            workflow {
                println('Hello!')
            }
            ''')
        service.updateNow()
        then:
        client.getDiagnostics(uri).isEmpty()
    }

}
