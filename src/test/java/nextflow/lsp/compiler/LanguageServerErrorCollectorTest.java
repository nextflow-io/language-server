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

import nextflow.script.control.PhaseAware;
import nextflow.script.control.Phases;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class LanguageServerErrorCollectorTest {

    static SyntaxErrorMessage makeErrorWithPhase(int phase) {
        var source = new SourceUnit("main.nf", "", new CompilerConfiguration(), null, null);
        return new SyntaxErrorMessage(new PhaseAwareError(phase), source);
    }

    static class PhaseAwareError extends SyntaxException implements PhaseAware {

        private final int phase;

        PhaseAwareError(int phase) {
            super("", EmptyExpression.INSTANCE);
            this.phase = phase;
        }

        @Override
        public int getPhase() {
            return phase;
        }
    }

    @Test
    void updateErrorsAfterGivenPhase() {
        var collector = new LanguageServerErrorCollector(new CompilerConfiguration());
        var newError = makeErrorWithPhase(Phases.INCLUDE_RESOLUTION);

        collector.addErrorAndContinue(makeErrorWithPhase(Phases.SYNTAX));
        collector.addErrorAndContinue(makeErrorWithPhase(Phases.INCLUDE_RESOLUTION));
        collector.addErrorAndContinue(makeErrorWithPhase(Phases.NAME_RESOLUTION));
        collector.addErrorAndContinue(makeErrorWithPhase(Phases.TYPE_CHECKING));
        collector.updatePhase(Phases.INCLUDE_RESOLUTION, List.of(newError));

        var errors = collector.getErrors();
        assertThat(errors).hasSize(2);
        var first = (SyntaxErrorMessage) errors.get(0);
        assertThat(((PhaseAware) first.getCause()).getPhase()).isEqualTo(Phases.SYNTAX);
        assertThat(errors.get(1)).isSameAs(newError);
    }

}
