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

import java.util.List;
import java.util.function.UnaryOperator;

import nextflow.lsp.ast.ProjectView;
import nextflow.lsp.services.LanguageServerConfiguration;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

/**
 * What a request body can see. Valid only during the request body
 * and only on the engine thread.
 *
 * @param configuration
 * @param project the project that the request routes to, or null for
 *   requests that are not tied to a project
 * @param allProjects every project, in folder order
 * @param cancelChecker throws a CancellationException once the request
 *   is cancelled
 * @param clientUri maps a URI to the URI that the client uses for the
 *   file: the URI that it opened the document with, if it is open,
 *   otherwise the given URI
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public record RequestContext(
    LanguageServerConfiguration configuration,
    ProjectView project,
    List<ProjectView> allProjects,
    CancelChecker cancelChecker,
    UnaryOperator<String> clientUri
) {}
