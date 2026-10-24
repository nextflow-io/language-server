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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Dependency edges between the entries of a project, as recorded
 * by their last build, with a reverse index.
 *
 * Engine-confined.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class DependencyGraph {

    private final Map<URI, Set<URI>> forward = new HashMap<>();

    private final Map<URI, Set<URI>> reverse = new HashMap<>();

    /**
     * Replace the outgoing edges of a node.
     *
     * @param from
     * @param targets
     */
    void setEdges(URI from, Collection<URI> targets) {
        var previous = forward.remove(from);
        if( previous != null ) {
            for( var target : previous ) {
                var sources = reverse.get(target);
                if( sources != null ) {
                    sources.remove(from);
                    if( sources.isEmpty() )
                        reverse.remove(target);
                }
            }
        }
        if( targets.isEmpty() )
            return;
        var sorted = new TreeSet<>(targets);
        forward.put(from, sorted);
        for( var target : sorted )
            reverse.computeIfAbsent(target, (k) -> new TreeSet<>()).add(from);
    }

    /**
     * Remove the outgoing edges of a node. Incoming edges are kept,
     * so that the dependents of a removed node can still be found.
     *
     * @param node
     */
    void removeNode(URI node) {
        setEdges(node, List.of());
    }

    /**
     * Get the direct dependents of a node, in sorted order.
     *
     * @param node
     */
    Set<URI> dependents(URI node) {
        return Collections.unmodifiableSet(reverse.getOrDefault(node, Collections.emptySet()));
    }

    /**
     * Get every node that depends on the given nodes, directly or
     * transitively, excluding the given nodes themselves.
     *
     * @param nodes
     */
    Set<URI> transitiveDependents(Collection<URI> nodes) {
        var result = new TreeSet<URI>();
        var queue = new ArrayDeque<URI>(nodes);
        while( !queue.isEmpty() ) {
            for( var dependent : dependents(queue.remove()) ) {
                if( !nodes.contains(dependent) && result.add(dependent) )
                    queue.add(dependent);
            }
        }
        return result;
    }

    /**
     * Get the strongly connected components reachable from the given
     * roots, in post-order: every component comes after the
     * components it depends on.
     *
     * The order is deterministic: roots are visited in the given
     * order, and neighbours in sorted order. Members of a component
     * are sorted.
     *
     * @param roots
     * @param neighbours
     */
    static List<List<URI>> components(Collection<URI> roots, Function<URI, Collection<URI>> neighbours) {
        return new Tarjan(neighbours).run(roots);
    }

    /**
     * Iterative Tarjan's algorithm, so that long include chains don't
     * overflow the stack.
     */
    private static final class Tarjan {

        private final Function<URI, Collection<URI>> neighbours;

        private final Map<URI, Integer> index = new HashMap<>();

        private final Map<URI, Integer> lowlink = new HashMap<>();

        private final ArrayDeque<URI> stack = new ArrayDeque<>();

        private final Set<URI> onStack = new HashSet<>();

        private final List<List<URI>> result = new ArrayList<>();

        private int counter;

        Tarjan(Function<URI, Collection<URI>> neighbours) {
            this.neighbours = neighbours;
        }

        private record Frame(URI node, List<URI> next, int[] position) {}

        List<List<URI>> run(Collection<URI> roots) {
            for( var root : roots ) {
                if( !index.containsKey(root) )
                    visit(root);
            }
            return result;
        }

        private void visit(URI root) {
            var frames = new ArrayDeque<Frame>();
            frames.push(enter(root));
            while( !frames.isEmpty() ) {
                var frame = frames.peek();
                if( frame.position[0] < frame.next.size() ) {
                    var target = frame.next.get(frame.position[0]++);
                    if( !index.containsKey(target) ) {
                        frames.push(enter(target));
                    }
                    else if( onStack.contains(target) ) {
                        lowlink.put(frame.node, Math.min(lowlink.get(frame.node), index.get(target)));
                    }
                    continue;
                }
                frames.pop();
                var node = frame.node;
                if( !frames.isEmpty() ) {
                    var parent = frames.peek().node;
                    lowlink.put(parent, Math.min(lowlink.get(parent), lowlink.get(node)));
                }
                if( lowlink.get(node).equals(index.get(node)) ) {
                    var component = new ArrayList<URI>();
                    URI member;
                    do {
                        member = stack.pop();
                        onStack.remove(member);
                        component.add(member);
                    } while( !member.equals(node) );
                    Collections.sort(component);
                    result.add(component);
                }
            }
        }

        private Frame enter(URI node) {
            index.put(node, counter);
            lowlink.put(node, counter);
            counter++;
            stack.push(node);
            onStack.add(node);
            var next = new ArrayList<>(neighbours.apply(node));
            Collections.sort(next);
            return new Frame(node, next, new int[] { 0 });
        }
    }

}
