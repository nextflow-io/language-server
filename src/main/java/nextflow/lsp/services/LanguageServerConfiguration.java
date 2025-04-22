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
package nextflow.lsp.services;

import java.util.Collections;
import java.util.List;

public record LanguageServerConfiguration(
    ErrorReportingMode errorReportingMode,
    List<String> excludePatterns,
    boolean extendedCompletion,
    boolean harshilAlignment,
    boolean maheshForm,
    int maxCompletionItems,
    boolean scanWorkspace,
    boolean sortDeclarations,
    boolean typeChecking
) {

    public static LanguageServerConfiguration defaults() {
        return new LanguageServerConfiguration(
            ErrorReportingMode.WARNINGS,
            Collections.emptyList(),
            false,
            false,
            false,
            100,
            false,
            false,
            false
        );
    }
}
