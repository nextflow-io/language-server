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

import java.util.Optional;

import org.eclipse.lsp4j.jsonrpc.CancelChecker;

/**
 * The language service state that the engine drives (projects,
 * builds, diagnostics). Every method is called on the engine thread.
 *
 * The engine owns the mailbox, the open documents, request
 * dispatch, parking and background scheduling. The state owns
 * everything else.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public interface EngineState {

    @FunctionalInterface
    interface Factory {
        /**
         * Create the state. The engine is not running yet, but its
         * accessors can be stored for later use.
         *
         * @param engine
         */
        EngineState create(Engine engine);
    }

    /**
     * Apply an input event.
     *
     * Document events are applied to the engine's document store
     * before this method is called, and are not delivered if they
     * don't affect an open document (non-file URI, unknown document).
     *
     * The state requests background work with
     * {@link Engine#scheduleBackground}.
     *
     * @param event
     */
    void apply(Event event);

    /**
     * Resolve the target of a request. Returns empty if the request
     * should get its empty value: unroutable, excluded or of an
     * unknown kind.
     *
     * A parked request is routed again every time it is checked,
     * so a route only needs to be valid within one engine task.
     *
     * @param spec
     */
    Optional<Route> route(RequestSpec spec);

    /**
     * Run one bounded slice of background work and publish its
     * results.
     *
     * @return true if more background work remains
     */
    boolean runSlice();

    /**
     * Release resources on shutdown. No further methods are called.
     */
    void shutdown();

    /**
     * The target of a request, resolved by the state.
     */
    interface Route {

        /**
         * Synchronously build what the request needs: the closure of
         * the request file (FILE), plus its dependents
         * (FILE_AND_DEPENDENTS) or the project configs
         * (PROJECT_CONFIGS), or every stale file of the target
         * projects (PROJECT, ALL; called only when they are indexed).
         */
        void prepare();

        /**
         * For PROJECT and ALL: whether every target project is
         * indexed and fresh.
         */
        boolean isSatisfied();

        /**
         * For PROJECT and ALL: whether every target project has
         * finished its initial index, even if it is stale.
         */
        boolean isIndexed();

        /**
         * Create the context for the request body.
         *
         * @param cancelChecker
         */
        RequestContext context(CancelChecker cancelChecker);
    }

}
