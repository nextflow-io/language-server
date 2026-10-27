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

package nextflow.lsp.ast;

import groovy.lang.groovydoc.Groovydoc;
import groovy.lang.groovydoc.GroovydocHolder;
import nextflow.script.ast.ASTNodeMarker;
import nextflow.script.ast.FeatureFlagNode;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.ProcessNodeV1;
import nextflow.script.ast.ScriptNode;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.dsl.FeatureFlagDsl;
import nextflow.script.dsl.ProcessDsl;
import nextflow.script.dsl.ScriptDsl;
import nextflow.script.namespaces.ChannelNamespace;
import nextflow.script.types.Channel;
import nextflow.script.types.shim.Iterable;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies the labels and documentation of AST nodes.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ASTNodeStringUtilsTest {

    static <T extends AnnotatedNode> T withGroovydoc(T node, String content) {
        node.putNodeMetaData(GroovydocHolder.DOC_COMMENT, new Groovydoc(content, node));
        return node;
    }

    /**
     * Assert that a description from an upstream annotation is
     * present, trimmed and unindented, without pinning its text.
     */
    static void assertDescription(String docs) {
        assertThat(docs).isNotBlank().isEqualTo(docs.strip());
        assertThat(docs.lines()).noneMatch(line -> line.startsWith(" "));
    }

    @Test
    void getLabelAndDocsForClass() {
        var classNode = new ClassNode(Channel.class);

        assertThat(ASTNodeStringUtils.getLabel(classNode)).isEqualTo("type Channel");

        var enumNode = withGroovydoc(new ClassNode(SequenceTypes.class), """
            /**
             * Enumeration of sequence types.
             */
            """);

        assertThat(ASTNodeStringUtils.getLabel(enumNode)).isEqualTo("enum SequenceTypes");
        assertThat(ASTNodeStringUtils.getDocumentation(enumNode)).isEqualTo("Enumeration of sequence types.");
    }

    @Test
    void getLabelAndDocsForFeatureFlag() {
        var ffn = new FeatureFlagNode("nextflow.enable.strict", null);
        ffn.target = new ClassNode(FeatureFlagDsl.class).getDeclaredField("strict");

        assertThat(ASTNodeStringUtils.getLabel(ffn)).isEqualTo("(feature flag) nextflow.enable.strict");
        assertDescription(ASTNodeStringUtils.getDocumentation(ffn));
    }

    @Test
    void getLabelAndDocsForWorkflow() {
        var entry = new WorkflowNode(null, EmptyStatement.INSTANCE);
        entry.putNodeMetaData(ASTNodeMarker.PIPELINE_SCRIPT, new ScriptNode(null));

        assertThat(ASTNodeStringUtils.getLabel(entry)).isEqualTo("workflow <entry>");

        var node = withGroovydoc(new WorkflowNode("FOO", EmptyStatement.INSTANCE), """
            /**
             * Run the FOO workflow.
             */
            """);

        assertThat(ASTNodeStringUtils.getLabel(node)).isEqualTo("""
            workflow FOO {
              take:
              <none>

              emit:
              <none>
            }""");
        assertThat(ASTNodeStringUtils.getDocumentation(node)).isEqualTo("Run the FOO workflow.");
    }

    @Test
    void getLabelAndDocsForProcess() {
        var empty = EmptyStatement.INSTANCE;
        var node = withGroovydoc(new ProcessNodeV1("BAR", empty, empty, empty, null, "script", empty, empty), """
            /**
             * Run the BAR process.
             */
            """);

        assertThat(ASTNodeStringUtils.getLabel(node)).isEqualTo("""
            process BAR {
              input:
              <none>

              output:
              <none>
            }""");
        assertThat(ASTNodeStringUtils.getDocumentation(node)).isEqualTo("Run the BAR process.");
    }

    @Test
    void getLabelAndDocsForFunction() {
        var params = new Parameter[] {
            new Parameter(ClassHelper.OBJECT_TYPE, "message"),
            new Parameter(ClassHelper.OBJECT_TYPE, "target")
        };
        var node = withGroovydoc(new FunctionNode("sayHello", ClassHelper.OBJECT_TYPE, params, null), """
            /**
             * Say hello to someone.
             */
            """);

        assertThat(ASTNodeStringUtils.getLabel(node)).isEqualTo("def sayHello(message, target)");
        assertThat(ASTNodeStringUtils.getDocumentation(node)).isEqualTo("Say hello to someone.");
    }

    @Test
    void getLabelAndDocsForBuiltinFunction() {
        var node = new ClassNode(ScriptDsl.class).getDeclaredMethods("env").get(0);

        assertThat(ASTNodeStringUtils.getLabel(node)).isEqualTo("env(name: String) -> String");
        assertDescription(ASTNodeStringUtils.getDocumentation(node));
    }

    @Test
    void getLabelAndDocsForNamespaceFunction() {
        var node = new ClassNode(ChannelNamespace.class).getDeclaredMethods("of").get(0);

        assertThat(ASTNodeStringUtils.getLabel(node)).isEqualTo("of(values...: E) -> Channel<E>");
        assertDescription(ASTNodeStringUtils.getDocumentation(node));
    }

    @Test
    void getLabelAndDocsForMethod() {
        var node = new ClassNode(Iterable.class).getDeclaredMethods("collect").get(0);

        assertThat(ASTNodeStringUtils.getLabel(node)).isEqualTo("Iterable collect(transform: (E) -> R) -> Iterable<R>");
        assertDescription(ASTNodeStringUtils.getDocumentation(node));
    }

    @Test
    void getLabelAndDocsForProcessDirective() {
        var node = new ClassNode(ProcessDsl.DirectiveDsl.class).getDeclaredMethods("executor").get(0);

        assertThat(ASTNodeStringUtils.getLabel(node)).isEqualTo("executor(value: String)");
        assertDescription(ASTNodeStringUtils.getDocumentation(node));
    }

}

// top-level so that its name has no enclosing class
enum SequenceTypes {}
