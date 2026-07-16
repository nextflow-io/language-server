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

import java.net.URI;
import java.util.List;

import nextflow.script.formatter.CommentReattacher;
import nextflow.script.formatter.FormattingOptions;
import org.eclipse.lsp4j.TextEdit;

public interface FormattingProvider {

    List<? extends TextEdit> formatting(URI uri, FormattingOptions options);

    /**
     * Determine whether formatting preserved every comment, as a safety
     * check before returning any edit. The lexing mode must match the
     * formatting visitor that produced the new text.
     *
     * @param oldText
     * @param newText
     * @param configFile whether to lex as a config file instead of a script
     */
    static boolean commentsPreserved(String oldText, String newText, boolean configFile) {
        return CommentReattacher.commentTexts(oldText, configFile)
            .equals(CommentReattacher.commentTexts(newText, configFile));
    }

}
