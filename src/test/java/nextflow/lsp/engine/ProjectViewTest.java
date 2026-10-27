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

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import nextflow.lsp.ast.ProjectView;
import nextflow.lsp.ast.ScriptView;
import nextflow.script.ast.ProcessNode;
import org.codehaus.groovy.ast.ASTNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static nextflow.lsp.TestUtils.thrownOnAnotherThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the provider-facing views of a project.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class ProjectViewTest {

    static final String MAIN = """
        include { HELLO } from './mod.nf'

        workflow {
            HELLO()
        }
        """;

    static final String MOD = """
        process HELLO {
            script:
            'echo hello'
        }
        """;

    @Test
    void showBuiltScriptsOfTheProject() {
        var f = TestServer.start();
        f.write("main.nf", MAIN);
        f.write("mod.nf", MOD);
        f.writeAndNotify("nextflow.config", "process.cpus = 1\n");
        f.settle();
        var mainUri = f.path("main.nf").toUri();
        var modUri = f.path("mod.nf").toUri();
        f.call(Need.FILE, "main.nf", null, (context) -> {
            var project = context.project();
            var scripts = project.scripts().orElseThrow();
            assertThat(scripts.getUris()).containsExactly(mainUri, modUri);
            assertThat(scripts.getProjectDir()).isEqualTo(TestWorkspace.root());
            assertThat(scripts.hasAST(mainUri)).isTrue();
            assertThat(scripts.getScriptNode(f.path("unknown.nf").toUri())).isNull();
            assertThat(scripts.getIncludeNodes(f.path("unknown.nf").toUri())).isEmpty();
            var process = scripts.getProcessNodes(modUri).get(0);
            assertThat(scripts.getURI(process)).isEqualTo(modUri);
            assertThat(scripts.getDefinitions()).hasAtLeastOneElementOfType(ProcessNode.class);
            // the config view doesn't own script nodes
            assertThat(project.configView().getURI(process)).isNull();
            assertThat(project.configView().getUris()).containsExactly(f.path("nextflow.config").toUri());
            return null;
        });
    }

    @Test
    void iterateEveryNode() {
        var f = TestServer.start();
        f.write("main.nf", MAIN);
        f.write("mod.nf", MOD);
        f.settle();
        f.call(Need.FILE, "main.nf", null, (context) -> {
            var scripts = context.project().scriptView();
            var nodes = scripts.getNodes();
            var expected = 0;
            for( var uri : scripts.getUris() )
                expected += scripts.getNodes(uri).size();
            var iterated = new ArrayList<ASTNode>();
            nodes.forEach(iterated::add);
            assertThat(nodes).hasSize(expected);
            assertThat(iterated).hasSize(expected);
            assertThat(iterated).allMatch(n -> scripts.getURI(n) != null);
            return null;
        });
    }

    @Test
    void canonicaliseRequestUris() {
        var f = TestServer.start();
        f.write("main.nf", MAIN);
        f.write("mod.nf", MOD);
        f.settle();
        var raw = URI.create(TestWorkspace.root().toUri() + "sub/../mod.nf");
        f.call(Need.FILE, "main.nf", null, (context) -> {
            var scripts = context.project().scriptView();
            assertThat(scripts.hasAST(raw)).isTrue();
            assertThat(scripts.getProcessNodes(raw)).hasSize(1);
            return null;
        });
    }

    @Test
    void showConfigFilesForConfigRequests() {
        var f = TestServer.start();
        f.write("nextflow.config", "includeConfig 'missing.config'\n");
        f.settle();
        var uri = f.path("nextflow.config").toUri();
        f.call(Need.FILE, "nextflow.config", null, (context) -> {
            var project = context.project();
            assertThat(project.scripts()).isEmpty();
            var configs = project.configView();
            assertThat(configs.getConfigNode(uri)).isNotNull();
            assertThat(configs.hasIncludeErrors(uri)).isTrue();
            assertThat(configs.hasErrors(uri)).isTrue();
            assertThat(configs.hasSyntaxErrors(uri)).isFalse();
            return null;
        });
    }

    @Test
    void showScriptsOfEveryProjectForProjectWideRequests() {
        var f = TestServer.start();
        f.write("main.nf", MAIN);
        f.write("mod.nf", MOD);
        f.settle();
        var roots = f.call(Need.ALL, null, List.<Path>of(), (context) -> {
            var result = new ArrayList<Path>();
            for( var project : context.allProjects() ) {
                assertThat(project.scripts()).isPresent();
                result.add(project.scripts().get().getProjectDir());
            }
            return result;
        });
        assertThat(roots).contains(TestWorkspace.root());
    }

    @Test
    void rejectViewAccessOutsideTheEngineThread() throws Exception {
        var f = TestServer.start();
        f.write("main.nf", MAIN);
        f.write("mod.nf", MOD);
        f.settle();
        var view = f.call(Need.FILE, "main.nf", (ScriptView) null, (context) -> context.project().scriptView());
        var project = f.call(Need.FILE, "main.nf", (ProjectView) null, RequestContext::project);
        for( Runnable access : List.<Runnable>of(view::getUris, view::getNodes, () -> view.getURI(null), project::scriptView, project::configView) )
            assertThat(thrownOnAnotherThread(access)).isInstanceOf(IllegalStateException.class);
    }

}
