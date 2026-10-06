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

package nextflow.lsp.spec

import spock.lang.Specification

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class PluginSpecCacheTest extends Specification {

    def 'should fetch a plugin spec' () {
        given:
        def psc = pluginSpecCache()

        when:
        def spec = psc.get('nf-prov', '1.6.0')
        then:
        spec.configScopes().containsKey('prov')
    }

    def 'should only track plugin versions for main config files' () {
        given:
        def psc = pluginSpecCache()
        def refs = [new PluginRef('nf-hello', '0.9.0')]

        when:
        psc.setCurrentVersions(uri('/project/other.config'), refs)
        then:
        psc.getCurrent(uri('/project/main.nf'), 'nf-hello') == null
    }

    def 'should return current plugin versions for a given script file' () {
        given:
        def ref1 = new PluginRef('nf-hello', '0.9.0')
        def ref2 = new PluginRef('nf-hello', '1.0.0')
        def spec1 = new PluginSpec([:], [], [], [])
        def spec2 = new PluginSpec([:], [], [], [])
        def psc = pluginSpecCache([(ref1): spec1, (ref2): spec2])

        // return nothing when no config files are registered
        expect:
        psc.getCurrent(uri('/project/main.nf'), 'nf-hello') == null

        // return nothing when script has no parent config file
        when:
        psc.setCurrentVersions(uri('/project/nextflow.config'), [new PluginRef('nf-hello', '0.9.0')])
        then:
        psc.getCurrent(uri('/other-project/main.nf'), 'nf-hello') == null

        // return nothing when requested plugin is not enabled
        when:
        psc.setCurrentVersions(uri('/project/nextflow.config'), [new PluginRef('nf-other', '1.0.0')])
        then:
        psc.getCurrent(uri('/project/main.nf'), 'nf-hello') == null

        // return plugin spec from nearest parent config
        when:
        psc.setCurrentVersions(uri('/project/nextflow.config'), [ref1])
        then:
        psc.getCurrent(uri('/project/main.nf'), 'nf-hello') == spec2
        psc.getCurrent(uri('/project/subdir/module.nf'), 'nf-hello') == spec2

        when:
        psc.setCurrentVersions(uri('/project/nextflow.config'), [ref1])
        psc.setCurrentVersions(uri('/project/subdir/nextflow.config'), [ref2])
        then:
        psc.getCurrent(uri('/project/main.nf'), 'nf-hello') == spec1
        psc.getCurrent(uri('/project/subdir/module.nf'), 'nf-hello') == spec2
    }

    def pluginSpecCache(Map entries = null) {
        def result = new PluginSpecCache('https://registry.nextflow.io/api/')
        if( entries ) {
            def field = PluginSpecCache.getDeclaredField('cache')
            field.setAccessible(true)
            field.set(result, entries)
        }
        return result
    }

    def uri(String path) {
        return URI.create('file://' + path)
    }

}
