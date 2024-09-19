/*
 * Copyright 2024, Seqera Labs
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
package nextflow.lsp.compiler;

import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.syntax.CSTNode;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class FutureWarning extends WarningMessage {

    public FutureWarning(int importance, String message, CSTNode context, SourceUnit owner) {
        super(importance, message, context, owner);
    }
}
