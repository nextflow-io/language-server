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

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest

import spock.lang.Ignore
import spock.lang.Specification

import static java.net.http.HttpClient.Redirect
import static java.net.http.HttpResponse.BodyHandlers

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigSchemaTest extends Specification {

    @Ignore
    def 'should fetch plugin definitions' () {
        given:
        def PLUGIN_REGITRY_URL = "http://localhost:8080/"
        // def PLUGIN_REGITRY_URL = "https://registry.nextflow.io/"
        def client = HttpClient.newBuilder().build()
        def baseUri = URI.create(PLUGIN_REGITRY_URL)

        when:
        def request = HttpRequest.newBuilder()
            .uri(baseUri.resolve("api/v1/plugins/nf-prov/1.5.0"))
            .GET()
            .header("Accept", "application/json")
            .build()
        def response = client.send(request, BodyHandlers.ofString())
        println request.headers()
        println response
        println response.headers()
        then:
        response.statusCode() == 200
        response.body() == '{"pluginRelease":{"version":"1.5.0","url":"http://localhost:5010/v2/plugins/nf-prov/blobs/sha256:338238a57c5faf7e263c11b856be16ac63cbbc95f93f762ea8eaa48f446b5c23","date":"2025-09-11T12:28:04.911512-05:00","sha512sum":"ed412172ee7d061266c31d53855d0b9c99a206444d231d4311db07cebaed5242507b71912f5a2cc3d5cad021d4ea1b9369282a486bad653e8985459599c7fc31","requires":">=25.07.0-edge","dependsOn":[],"definitions":"[{\\\"type\\\":\\\"ConfigScope\\\",\\\"spec\\\":{\\\"name\\\":\\\"prov\\\",\\\"description\\\":\\\"The `prov` scope allows you to configure the `nf-prov` plugin.\\n\\n[Read more](https://nextflow.io/docs/latest/reference/config.html#prov)\\n\\\",\\\"children\\\":{\\\"formats\\\":{\\\"type\\\":\\\"ConfigScope\\\",\\\"spec\\\":{\\\"name\\\":\\\"formats\\\",\\\"description\\\":\\\"Configuration scope for the desired output formats.\\n\\n[Read more](https://nextflow.io/docs/latest/reference/config.html#formats)\\n\\\",\\\"children\\\":{\\\"wrroc\\\":{\\\"type\\\":\\\"ConfigScope\\\",\\\"spec\\\":{\\\"name\\\":\\\"wrroc\\\",\\\"description\\\":\\\"Configuration scope for the WRROC output format.\\n\\n[Read more](https://nextflow.io/docs/latest/reference/config.html#wrroc)\\n\\\",\\\"children\\\":{\\\"license\\\":{\\\"type\\\":\\\"ConfigOption\\\",\\\"spec\\\":{\\\"description\\\":\\\"The license for the Workflow Run RO-Crate.\\\",\\\"type\\\":\\\"String\\\"}},\\\"file\\\":{\\\"type\\\":\\\"ConfigOption\\\",\\\"spec\\\":{\\\"description\\\":\\\"The file name of the Workflow Run RO-Crate.\\\",\\\"type\\\":\\\"String\\\"}},\\\"overwrite\\\":{\\\"type\\\":\\\"ConfigOption\\\",\\\"spec\\\":{\\\"description\\\":\\\"When `true` overwrites any existing Workflow Run RO-Crate with the same name (default: `false`).\\\",\\\"type\\\":\\\"boolean\\\"}}}}},\\\"dag\\\":{\\\"type\\\":\\\"ConfigScope\\\",\\\"spec\\\":{\\\"name\\\":\\\"dag\\\",\\\"description\\\":\\\"Configuration scope for the DAG output format.\\n\\n[Read more](https://nextflow.io/docs/latest/reference/config.html#dag)\\n\\\",\\\"children\\\":{\\\"file\\\":{\\\"type\\\":\\\"ConfigOption\\\",\\\"spec\\\":{\\\"description\\\":\\\"The file name of the DAG diagram.\\\",\\\"type\\\":\\\"String\\\"}},\\\"overwrite\\\":{\\\"type\\\":\\\"ConfigOption\\\",\\\"spec\\\":{\\\"description\\\":\\\"When `true` overwrites any existing DAG diagram with the same name (default: `false`).\\\",\\\"type\\\":\\\"boolean\\\"}}}}},\\\"bco\\\":{\\\"type\\\":\\\"ConfigScope\\\",\\\"spec\\\":{\\\"name\\\":\\\"bco\\\",\\\"description\\\":\\\"Configuration scope for the BCO output format.\\n\\n[Read more](https://nextflow.io/docs/latest/reference/config.html#bco)\\n\\\",\\\"children\\\":{\\\"file\\\":{\\\"type\\\":\\\"ConfigOption\\\",\\\"spec\\\":{\\\"description\\\":\\\"The file name of the BCO manifest.\\\",\\\"type\\\":\\\"String\\\"}},\\\"overwrite\\\":{\\\"type\\\":\\\"ConfigOption\\\",\\\"spec\\\":{\\\"description\\\":\\\"When `true` overwrites any existing BCO manifest with the same name (default: `false`).\\\",\\\"type\\\":\\\"boolean\\\"}}}}}}}},\\\"enabled\\\":{\\\"type\\\":\\\"ConfigOption\\\",\\\"spec\\\":{\\\"description\\\":\\\"Create the provenance report (default: `true` if plugin is loaded).\\\",\\\"type\\\":\\\"boolean\\\"}}}}}]"}}'
    }

}
