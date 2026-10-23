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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import nextflow.lsp.ast.ProjectView;
import nextflow.lsp.services.LanguageServerConfiguration;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

/**
 * Scriptable engine state that records what the engine asks of it.
 *
 * Background work is modelled as a number of pending slices. When
 * the last one runs, PROJECT/ALL requests become satisfied (unless
 * {@link #satisfyWhenDone} is false).
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class FakeState implements EngineState {

    final Engine engine;

    final List<String> log = new ArrayList<>();

    boolean satisfied = true;

    boolean indexed = true;

    int pendingSlices;

    boolean satisfyWhenDone = true;

    final Set<String> unroutable = new HashSet<>();

    RuntimeException applyError;

    RuntimeException routeError;

    RuntimeException prepareError;

    RuntimeException sliceError;

    RuntimeException shutdownError;

    int slices;

    boolean shutdownCalled;

    FakeState(Engine engine) {
        this.engine = engine;
    }

    @Override
    public void apply(Event event) {
        log.add("event " + event.getClass().getSimpleName());
        if( applyError != null )
            throw applyError;
        if( event instanceof Event.DidOpen || event instanceof Event.DidChange || event instanceof Event.DidClose )
            engine.scheduleBackground();
    }

    @Override
    public Optional<Route> route(RequestSpec spec) {
        log.add("route " + spec.method());
        if( routeError != null )
            throw routeError;
        if( spec.uri() != null && unroutable.contains(spec.uri()) )
            return Optional.empty();
        return Optional.of(new FakeRoute(spec));
    }

    @Override
    public boolean runSlice() {
        slices++;
        log.add("slice");
        if( sliceError != null )
            throw sliceError;
        if( pendingSlices > 0 ) {
            pendingSlices--;
            if( pendingSlices == 0 && satisfyWhenDone )
                satisfied = true;
        }
        return pendingSlices > 0;
    }

    @Override
    public void shutdown() {
        shutdownCalled = true;
        log.add("shutdown");
        if( shutdownError != null )
            throw shutdownError;
    }

    private final class FakeRoute implements Route {

        private final RequestSpec spec;

        FakeRoute(RequestSpec spec) {
            this.spec = spec;
        }

        @Override
        public void prepare() {
            log.add("prepare " + spec.method());
            if( prepareError != null )
                throw prepareError;
        }

        @Override
        public boolean isSatisfied() {
            return satisfied;
        }

        @Override
        public boolean isIndexed() {
            return indexed;
        }

        @Override
        public RequestContext context(CancelChecker cancelChecker) {
            return new RequestContext() {
                @Override
                public LanguageServerConfiguration configuration() {
                    return LanguageServerConfiguration.defaults();
                }

                @Override
                public ProjectView project() {
                    return null;
                }

                @Override
                public List<ProjectView> allProjects() {
                    return List.of();
                }

                @Override
                public CancelChecker cancelChecker() {
                    return cancelChecker;
                }
            };
        }
    }

}
