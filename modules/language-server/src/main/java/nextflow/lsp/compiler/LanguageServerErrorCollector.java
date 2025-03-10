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
package nextflow.lsp.compiler;

import java.util.List;

import nextflow.script.control.LazyErrorCollector;
import nextflow.script.control.PhaseAware;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;

/**
 * Error collector that defers error reporting and can
 * be updated on re-compilation.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class LanguageServerErrorCollector extends LazyErrorCollector {

    public LanguageServerErrorCollector(CompilerConfiguration configuration) {
        super(configuration);
    }

    /**
     * Remove all errors on or after a given phase and replace them
     * with new errors.
     *
     * @param phase
     * @param newErrors
     */
    public void updatePhase(int phase, List<? extends Message> newErrors) {
        // var oldErrors = errors;
        // errors = null;
        if( errors != null )
            errors.removeIf(message -> isErrorPhase(message, phase));
        for( var errorMessage : newErrors )
            addErrorAndContinue(errorMessage);
    }

    private static boolean isErrorPhase(Message message, int phase) {
        return message instanceof SyntaxErrorMessage sem
            && sem.getCause() instanceof PhaseAware pa
            && pa.getPhase() >= phase;
    }

}
