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

package nextflow.lsp.util

import org.eclipse.lsp4j.Position
import spock.lang.Specification

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class PositionsTest extends Specification {

    int sign(int n) {
        if( n > 0 )
            return 1
        if( n < 0 )
            return -1
        return 0
    }

    def 'should compare positions' () {
        given:
        def a = new Position(a1, a2)
        def b = new Position(b1, b2)

        expect:
        result == sign(Positions.COMPARATOR.compare(a, b))

        where:
        a1 | a2 | b1 | b2 | result
        0  | 0  | 0  | 0  | 0
        1  | 0  | 0  | 1  | 1
        0  | 1  | 1  | 0  | -1
    }

    def 'should convert position to offset' () {
        given:
        def script = '''
            workflow {
                println 'Hello World!'
            }
            '''.stripIndent(true).trim()

        expect:
        Positions.getOffset(script, new Position(0, 0)) == 0
        Positions.getOffset(script, new Position(1, 0)) == 11
        Positions.getOffset(script, new Position(2, 0)) == 38
        Positions.getOffset(script, new Position(3, 0)) == -1
    }

    def 'should convert offset to position' () {
        given:
        def script = '''
            workflow {
                println 'Hello World!'
            }
            '''.stripIndent(true).trim()

        expect:
        Positions.getPosition(script, 0) == new Position(0, 0)
        Positions.getPosition(script, 11) == new Position(1, 0)
        Positions.getPosition(script, 38) == new Position(2, 0)
        Positions.getPosition(script, 50) == new Position(-1, -1)
    }

}
