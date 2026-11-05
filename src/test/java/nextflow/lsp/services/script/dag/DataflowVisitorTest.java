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
package nextflow.lsp.services.script.dag;

import java.util.Collection;
import java.util.List;

import nextflow.lsp.TestServer;
import nextflow.lsp.TestWorkspace;
import nextflow.lsp.engine.Need;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies the dataflow graph built from a workflow, including the
 * expansion of subworkflow calls into take, call, and emit nodes, and
 * the labels of control nodes.
 */
@ExtendWith(TestWorkspace.class)
class DataflowVisitorTest {

    /**
     * Open main.nf with the given text and build the graph of a workflow
     * ("<entry>" for the entry workflow) from the compiled script.
     */
    static Graph graph(String text, String workflow, boolean expandWorkflowCalls) {
        var server = TestServer.start();
        server.open("main.nf", text);
        return graph(server, workflow, expandWorkflowCalls);
    }

    static Graph graph(TestServer server, String workflow, boolean expandWorkflowCalls) {
        server.settle();
        assertThat(server.diagnostics("main.nf"))
            .filteredOn(d -> d.getSeverity() == DiagnosticSeverity.Error)
            .isEmpty();
        var uri = server.path("main.nf").toUri();
        return server.call(Need.FILE, "main.nf", (Graph) null, (context) -> {
            var ast = context.project().scripts().orElseThrow();
            var visitor = new DataflowVisitor(ast.getSourceUnit(uri), ast, false, expandWorkflowCalls);
            visitor.visit();
            return visitor.getGraph(workflow);
        });
    }

    static List<Node> nodes(Graph graph, String label) {
        return graph.nodes.values().stream()
            .filter(n -> n.label.equals(label))
            .sorted((a, b) -> Integer.compare(a.id, b.id))
            .toList();
    }

    static Node node(Graph graph, String label) {
        var result = nodes(graph, label);
        assertThat(result).as("nodes labelled '%s' in %s", label, graph.nodes.values()).hasSize(1);
        return result.get(0);
    }

    static Node node(Graph graph, Node.Type type) {
        var result = graph.nodes.values().stream().filter(n -> n.type == type).toList();
        assertThat(result).as("nodes of type %s in %s", type, graph.nodes.values()).hasSize(1);
        return result.get(0);
    }

    static List<Node.Type> types(Collection<Node> nodes) {
        return nodes.stream().map(n -> n.type).distinct().toList();
    }

    static final String FOO = """
        workflow FOO {
            take:
            a
            b

            main:
            c = a.mix(b)
            d = a.map { v -> v * 2 }

            emit:
            c
            d
        }
        """;

    @Test
    void expandSubworkflowCallIntoTakeCallAndEmitNodes() {
        var graph = graph(FOO + """

            workflow {
                x = channel.of(1)
                y = channel.of(2)
                FOO(x, y)
            }
            """, "<entry>", true);

        var x = node(graph, "x");
        var y = node(graph, "y");
        var takeA = node(graph, "take:a");
        var takeB = node(graph, "take:b");
        var call = node(graph, "FOO");
        var emitC = node(graph, "emit:c");
        var emitD = node(graph, "emit:d");

        assertThat(takeA.type).isEqualTo(Node.Type.INPUT);
        assertThat(takeB.type).isEqualTo(Node.Type.INPUT);
        assertThat(call.type).isEqualTo(Node.Type.OPERATOR);
        assertThat(emitC.type).isEqualTo(Node.Type.OUTPUT);
        assertThat(emitD.type).isEqualTo(Node.Type.OUTPUT);

        assertThat(takeA.preds).containsExactly(x);
        assertThat(takeB.preds).containsExactly(y);
        assertThat(call.preds).containsExactlyInAnyOrder(takeA, takeB);
        assertThat(emitC.preds).containsExactly(call);
        assertThat(emitD.preds).containsExactly(call);
    }

    @Test
    void connectNamedWorkflowOutputToMatchingEmitNode() {
        var graph = graph(FOO + """

            workflow {
                x = channel.of(1)
                FOO(x, x)
                r = FOO.out.d
            }
            """, "<entry>", true);

        assertThat(node(graph, "r").preds).containsExactly(node(graph, "emit:d"));
    }

    @Test
    void notBindEmitNamesAsVariables() {
        var graph = graph(FOO + """

            workflow {
                x = channel.of(1)
                FOO(x, x)
                c = channel.of(3)
                r = c
            }
            """, "<entry>", true);

        assertThat(node(graph, "r").preds).containsExactly(node(graph, "c"));
    }

    @Test
    void keepEmitsOfDifferentSubworkflowsWithTheSameNameApart() {
        var graph = graph("""
            workflow FOO {
                take:
                a

                main:
                c = a.map { v -> v + 1 }

                emit:
                c
            }

            workflow BAR {
                take:
                a

                main:
                c = a.map { v -> v - 1 }

                emit:
                c
            }

            workflow {
                x = channel.of(1)
                FOO(x)
                BAR(x)
                r = FOO.out.c
                s = BAR.out.c
            }
            """, "<entry>", true);

        var emits = nodes(graph, "emit:c");
        assertThat(emits).hasSize(2);
        var fooEmit = emits.stream().filter(n -> n.preds.contains(node(graph, "FOO"))).findFirst().orElseThrow();
        var barEmit = emits.stream().filter(n -> n.preds.contains(node(graph, "BAR"))).findFirst().orElseThrow();

        assertThat(node(graph, "r").preds).containsExactly(fooEmit);
        assertThat(node(graph, "s").preds).containsExactly(barEmit);
    }

    @Test
    void keepEmitsOfRepeatedCallsToTheSameSubworkflowApart() {
        var graph = graph("""
            workflow FOO {
                take:
                a

                main:
                c = a.map { v -> v + 1 }

                emit:
                c
            }

            workflow {
                x = channel.of(1)
                y = channel.of(2)
                FOO(x)
                r = FOO.out.c
                FOO(y)
                s = FOO.out.c
            }
            """, "<entry>", true);

        var takes = nodes(graph, "take:a");
        var calls = nodes(graph, "FOO");
        var emits = nodes(graph, "emit:c");
        assertThat(takes).hasSize(2);
        assertThat(calls).hasSize(2);
        assertThat(emits).hasSize(2);

        assertThat(takes.get(0).preds).containsExactly(node(graph, "x"));
        assertThat(takes.get(1).preds).containsExactly(node(graph, "y"));
        assertThat(calls.get(0).preds).containsExactly(takes.get(0));
        assertThat(calls.get(1).preds).containsExactly(takes.get(1));
        assertThat(emits.get(0).preds).containsExactly(calls.get(0));
        assertThat(emits.get(1).preds).containsExactly(calls.get(1));
        assertThat(node(graph, "r").preds).containsExactly(emits.get(0));
        assertThat(node(graph, "s").preds).containsExactly(emits.get(1));
    }

    @Test
    void expandSubworkflowCallInPipe() {
        var graph = graph("""
            workflow FOO {
                take:
                a

                main:
                c = a.map { v -> v + 1 }

                emit:
                c
            }

            workflow {
                x = channel.of(1)
                x | FOO
            }
            """, "<entry>", true);

        var take = node(graph, "take:a");
        var call = node(graph, "FOO");
        var emit = node(graph, "emit:c");
        assertThat(take.type).isEqualTo(Node.Type.INPUT);
        assertThat(take.preds).containsExactly(node(graph, "x"));
        assertThat(call.preds).containsExactly(take);
        assertThat(emit.type).isEqualTo(Node.Type.OUTPUT);
        assertThat(emit.preds).containsExactly(call);
    }

    @Test
    void connectBareWorkflowOutputToSoleEmitNode() {
        var graph = graph("""
            workflow FOO {
                take:
                a

                main:
                c = a.map { v -> v + 1 }

                emit:
                c
            }

            workflow {
                x = channel.of(1)
                FOO(x)
                r = FOO.out
            }
            """, "<entry>", true);

        assertThat(node(graph, "r").preds).containsExactly(node(graph, "emit:c"));
    }

    @Test
    void connectBareWorkflowOutputToCallNodeWhenThereAreSeveralEmits() {
        var graph = graph(FOO + """

            workflow {
                x = channel.of(1)
                FOO(x, x)
                r = FOO.out
            }
            """, "<entry>", true);

        assertThat(node(graph, "r").preds).containsExactly(node(graph, "FOO"));
    }

    @Test
    void notExpandProcessCalls() {
        var graph = graph("""
            process HELLO {
                input:
                val x

                output:
                val x, emit: y

                script:
                true
            }

            workflow {
                x = channel.of(1)
                HELLO(x)
                r = HELLO.out.y
            }
            """, "<entry>", true);

        var call = node(graph, "HELLO");
        assertThat(call.type).isEqualTo(Node.Type.OPERATOR);
        assertThat(call.preds).containsExactly(node(graph, "x"));
        assertThat(node(graph, "r").preds).containsExactly(call);
        assertThat(types(graph.nodes.values())).doesNotContain(Node.Type.INPUT, Node.Type.OUTPUT);
    }

    @Test
    void keepWorkflowCallsAsSingleOperatorNodesWhenNotExpanding() {
        var graph = graph(FOO + """

            workflow {
                x = channel.of(1)
                y = channel.of(2)
                FOO(x, y)
                r = FOO.out.c
            }
            """, "<entry>", false);

        var call = node(graph, "FOO");
        assertThat(call.type).isEqualTo(Node.Type.OPERATOR);
        assertThat(call.preds).containsExactlyInAnyOrder(node(graph, "x"), node(graph, "y"));
        assertThat(node(graph, "r").preds).containsExactly(call);
        assertThat(types(graph.nodes.values())).doesNotContain(Node.Type.INPUT, Node.Type.OUTPUT);
    }

    @Test
    void renderTakeAndEmitNodesAsRoundedNodes() {
        var graph = graph("""
            workflow FOO {
                take:
                a

                main:
                c = a.map { v -> v + 1 }

                emit:
                c
            }

            workflow {
                x = channel.of(1)
                FOO(x)
            }
            """, "<entry>", true);
        var take = node(graph, "take:a");
        var call = node(graph, "FOO");
        var emit = node(graph, "emit:c");

        var mmd = new MermaidRenderer("TB", false).render(null, graph);

        assertThat(mmd.lines().map(String::strip)).contains(
            String.format("v%d(\"take:a\")", take.id),
            String.format("v%d([FOO])", call.id),
            String.format("v%d(\"emit:c\")", emit.id),
            String.format("v%d --> v%d", take.id, call.id),
            String.format("v%d --> v%d", call.id, emit.id));
    }

    @Test
    void labelIfControlNodeWithConditionWithoutParentheses() {
        var graph = graph("""
            workflow {
                if( params.flag == 'a' ) {
                    x = channel.of(1)
                }
            }
            """, "<entry>", false);

        assertThat(node(graph, Node.Type.CONTROL).label).isEqualTo("params.flag == 'a'");
    }

    @Test
    void stripOnlyTheOuterParenthesesOfIfCondition() {
        var graph = graph("""
            workflow {
                if( (params.a) || (params.b) ) {
                    x = channel.of(1)
                }
            }
            """, "<entry>", false);

        assertThat(node(graph, Node.Type.CONTROL).label).isEqualTo("(params.a) || (params.b)");
    }

    @Test
    void labelIfControlNodeWithMultiLineCondition() {
        var graph = graph("""
            workflow {
                if( params.a &&
                    params.b ) {
                    x = channel.of(1)
                }
            }
            """, "<entry>", false);

        assertThat(node(graph, Node.Type.CONTROL).label).isEqualTo("params.a &&\n        params.b");
    }

    @Test
    void labelTernaryControlNodeWithCondition() {
        var graph = graph("""
            workflow {
                x = params.flag ? channel.of(1) : channel.of(2)
            }
            """, "<entry>", false);

        assertThat(node(graph, Node.Type.CONTROL).label).isEqualTo("params.flag");
    }

    @Test
    void labelTernaryControlNodeWithMultiLineCondition() {
        var graph = graph("""
            workflow {
                x = params.a
                    && params.b
                    ? channel.of(1)
                    : channel.of(2)
            }
            """, "<entry>", false);

        assertThat(node(graph, Node.Type.CONTROL).label).isEqualTo("params.a\n        && params.b");
    }

    @Test
    void labelControlNodeWithConditionFromOpenDocumentInsteadOfFile() {
        var server = TestServer.start();
        server.write("main.nf", """
            workflow {
                if( params.on_disk ) {
                    x = channel.of(1)
                }
            }
            """);
        server.open("main.nf", """
            workflow {
                // shifted by a line
                if( params.in_editor ) {
                    x = channel.of(1)
                }
            }
            """);

        var graph = graph(server, "<entry>", false);

        assertThat(node(graph, Node.Type.CONTROL).label).isEqualTo("params.in_editor");
    }
}
