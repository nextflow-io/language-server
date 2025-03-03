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

package nextflow.lsp.compiler

import nextflow.script.control.PhaseAware
import nextflow.script.control.Phases
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException
import spock.lang.Specification

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class LanguageServerErrorCollectorTest extends Specification {

    def 'should update errors after a given phase' () {
        given:
        def collector = new LanguageServerErrorCollector(new CompilerConfiguration())
        def newError = makeErrorWithPhase(Phases.INCLUDE_RESOLUTION)

        when:
        collector.addErrorAndContinue(makeErrorWithPhase(Phases.SYNTAX))
        collector.addErrorAndContinue(makeErrorWithPhase(Phases.NAME_RESOLUTION))
        collector.addErrorAndContinue(makeErrorWithPhase(Phases.INCLUDE_RESOLUTION))
        collector.addErrorAndContinue(makeErrorWithPhase(Phases.TYPE_INFERENCE))
        and:
        collector.updatePhase(Phases.INCLUDE_RESOLUTION, [ newError ])
        then:
        collector.getErrors().size() == 3
        collector.getErrors()[0].cause.phase == Phases.SYNTAX
        collector.getErrors()[1].cause.phase == Phases.NAME_RESOLUTION
        collector.getErrors()[2] === newError
    }

    def makeErrorWithPhase(phase) {
        return new SyntaxErrorMessage(new MockError(phase), Mock(SourceUnit))
    }

    class MockError extends SyntaxException implements PhaseAware {

        int phase

        MockError(int phase) {
            super('', EmptyExpression.INSTANCE)
            this.phase = phase
        }
    }

}
