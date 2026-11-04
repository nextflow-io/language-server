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

import nextflow.config.ast.ConfigNode;
import nextflow.lsp.ast.ConfigView;
import nextflow.script.control.PhaseAware;
import nextflow.script.control.Phases;

/**
 * View of the built config files of a project.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class ConfigEntryView extends EntryView implements ConfigView {

    ConfigEntryView(Engine engine, Project project) {
        super(engine, project, FileKind.CONFIG);
    }

    @Override
    public boolean hasIncludeErrors(URI uri) {
        return getErrors(uri).stream()
            .anyMatch(error -> error instanceof PhaseAware pa && pa.getPhase() == Phases.INCLUDE_RESOLUTION);
    }

    @Override
    public ConfigNode getConfigNode(URI uri) {
        var sourceUnit = getSourceUnit(uri);
        return sourceUnit != null && sourceUnit.getAST() instanceof ConfigNode cn ? cn : null;
    }

}
