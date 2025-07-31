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

package nextflow.lsp.services.script.dag

import nextflow.lsp.services.LanguageServerConfiguration
import nextflow.lsp.services.script.ScriptService
import spock.lang.Specification

import static nextflow.lsp.TestUtils.*
import static nextflow.lsp.util.JsonUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class PreviewDagTest extends Specification {

    boolean checkDagPreview(ScriptService service, String uri, String name, String source, String mmd) {
        open(service, uri, source.stripIndent())
        def response = service.executeCommand('nextflow.server.previewDag', [asJson(uri), asJson(name)], LanguageServerConfiguration.defaults())
        assert response.result == mmd.stripIndent()
        return true
    }

    def 'should handle an if-else statement' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkDagPreview(service, uri, null,
            '''\
            workflow {
                if (params.echo) {
                    echo = TOUCH(params.echo)
                } else {
                    echo = DEFAULT()
                }
                APPEND(echo)
            }

            process TOUCH {
                input:
                val x

                script:
                true
            }

            process DEFAULT {
                true
            }

            process APPEND {
                input:
                val x

                script:
                true
            }
            ''',
            """\
            flowchart TB
              subgraph " "
                subgraph params
                  v0["echo"]
                end
                v1{ }
                v7([APPEND])
                click v7 href "$uri" _blank
                subgraph s1[" "]
                  v2([TOUCH])
                  click v2 href "$uri" _blank
                end
                subgraph s2[" "]
                  v4([DEFAULT])
                  click v4 href "$uri" _blank
                end
                v0 --> v1
                v0 --> v2
                v2 --> v7
                v4 --> v7
                v1 --> s1
                v1 --> s2
              end
            """
        )
    }

    def 'should collapse empty if-else statement' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkDagPreview(service, uri, null,
            '''\
            workflow {
                main:
                if( params.echo )
                  echo = 1
                else
                  echo = 0

                publish:
                echo = echo
            }
            ''',
            """\
            flowchart TB
              subgraph " "
                subgraph params
                  v0["echo"]
                end
                subgraph publish
                  v5["echo"]
                end
                v0 --> v5
              end
            """
        )

        checkDagPreview(service, uri, 'ECHO',
            '''\
            workflow ECHO {
                take:
                debug

                main:
                if( debug )
                    echo = 1
                else
                    echo = 0

                emit:
                echo = echo
            }
            ''',
            """\
            flowchart TB
              subgraph ECHO
                subgraph take
                  v0["debug"]
                end
                subgraph emit
                  v5["echo"]
                end
                v0 --> v5
              end
            """
        )
    }

    def 'should handle a ternary expression' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkDagPreview(service, uri, null,
            '''\
            workflow {
                echo = params.echo
                    ? TOUCH(params.echo)
                    : DEFAULT()
                APPEND(echo)
            }

            process TOUCH {
                input:
                val x

                script:
                true
            }

            process DEFAULT {
                true
            }

            process APPEND {
                input:
                val x

                script:
                true
            }
            ''',
            """\
            flowchart TB
              subgraph " "
                subgraph params
                  v0["echo"]
                end
                v1{ }
                v5([APPEND])
                click v5 href "$uri" _blank
                subgraph s1[" "]
                  v2([TOUCH])
                  click v2 href "$uri" _blank
                end
                subgraph s2[" "]
                  v3([DEFAULT])
                  click v3 href "$uri" _blank
                end
                v0 --> v1
                v0 --> v2
                v2 --> v5
                v3 --> v5
                v1 --> s1
                v1 --> s2
              end
            """
        )
    }

    def 'should collapse empty ternary expression' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkDagPreview(service, uri, null,
            '''\
            workflow {
                main:
                echo = params.echo ? 1 : 0

                publish:
                echo = echo
            }
            ''',
            """\
            flowchart TB
              subgraph " "
                subgraph params
                  v0["echo"]
                end
                subgraph publish
                  v3["echo"]
                end
                v0 --> v3
              end
            """
        )
    }

    def 'should handle variable reassignment in an if statement' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkDagPreview(service, uri, null,
            '''\
            workflow {
                main:
                ch_versions = channel.empty()

                FOO()
                ch_versions = ch_versions.mix( FOO.out.versions )

                if (params.bar) {
                    BAR()
                    ch_versions = ch_versions.mix( BAR.out.versions )
                }

                publish:
                versions = ch_versions
            }

            process FOO {
                output:
                val 'versions', emit: versions

                script:
                true
            }

            process BAR {
                output:
                val 'versions', emit: versions

                script:
                true
            }
            ''',
            """\
            flowchart TB
              subgraph " "
                subgraph params
                  v3["bar"]
                end
                v1([FOO])
                click v1 href "$uri" _blank
                v4{ }
                subgraph s1[" "]
                  v5([BAR])
                  click v5 href "$uri" _blank
                end
                subgraph publish
                  v8["versions"]
                end
                v3 --> v4
                v1 --> v8
                v5 --> v8
                v4 --> s1
              end
            """
        )
    }

}
