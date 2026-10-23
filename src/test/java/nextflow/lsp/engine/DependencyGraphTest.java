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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyGraphTest {

    static URI uri(String name) {
        return URI.create("file:///w/" + name);
    }

    static List<List<String>> names(List<List<URI>> components) {
        return components.stream()
            .map(c -> c.stream().map(u -> u.getPath().substring(3)).toList())
            .toList();
    }

    static DependencyGraph graph(Map<String, List<String>> edges) {
        var graph = new DependencyGraph();
        edges.forEach((from, targets) -> graph.setEdges(uri(from), targets.stream().map(DependencyGraphTest::uri).toList()));
        return graph;
    }

    @Test
    void orderDependenciesFirst() {
        var graph = graph(Map.of("a", List.of("c", "b"), "b", List.of("c")));
        assertThat(names(graph.components(List.of(uri("a")))))
            .containsExactly(List.of("c"), List.of("b"), List.of("a"));
    }

    @Test
    void keepCyclesTogether() {
        var graph = graph(Map.of("a", List.of("b"), "b", List.of("c"), "c", List.of("b", "d")));
        assertThat(names(graph.components(List.of(uri("a")))))
            .containsExactly(List.of("d"), List.of("b", "c"), List.of("a"));
    }

    @Test
    void visitRootsInTheGivenOrder() {
        var graph = graph(Map.of("a", List.of("x"), "b", List.of("y")));
        assertThat(names(graph.components(List.of(uri("b"), uri("a")))))
            .containsExactly(List.of("y"), List.of("b"), List.of("x"), List.of("a"));
    }

    @Test
    void trackDependents() {
        var graph = graph(Map.of("a", List.of("b"), "b", List.of("c"), "d", List.of("c")));
        assertThat(graph.dependents(uri("c"))).containsExactly(uri("b"), uri("d"));
        assertThat(graph.transitiveDependents(Set.of(uri("c")))).containsExactly(uri("a"), uri("b"), uri("d"));

        graph.setEdges(uri("d"), List.of());
        assertThat(graph.dependents(uri("c"))).containsExactly(uri("b"));
        graph.removeNode(uri("b"));
        assertThat(graph.dependents(uri("c"))).isEmpty();
        // incoming edges of a removed node are kept
        assertThat(graph.dependents(uri("b"))).containsExactly(uri("a"));
    }

    @Test
    void handleLongChainsWithoutRecursion() {
        var graph = new DependencyGraph();
        var count = 20_000;
        IntStream.range(0, count - 1).forEach(i -> graph.setEdges(uri("n" + i), List.of(uri("n" + (i + 1)))));
        var components = graph.components(List.of(uri("n0")));
        assertThat(components).hasSize(count);
        assertThat(components.get(0)).containsExactly(uri("n" + (count - 1)));
    }

}
