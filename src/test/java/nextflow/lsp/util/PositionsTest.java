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

package nextflow.lsp.util;

import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class PositionsTest {

    static final String SCRIPT = """
        workflow {
            println 'Hello World!'
        }""";

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        0 | 0 | 0 | 0 |  0
        1 | 0 | 0 | 1 |  1
        0 | 1 | 1 | 0 | -1
        1 | 2 | 1 | 5 | -1
        1 | 5 | 1 | 2 |  1
        """)
    void comparePositions(int a1, int a2, int b1, int b2, int result) {
        var a = new Position(a1, a2);
        var b = new Position(b1, b2);
        assertThat(Integer.signum(Positions.COMPARATOR.compare(a, b))).isEqualTo(result);
    }

    @Test
    void convertPositionToOffset() {
        assertThat(Positions.getOffset(SCRIPT, new Position(0, 0))).isEqualTo(0);
        assertThat(Positions.getOffset(SCRIPT, new Position(1, 0))).isEqualTo(11);
        assertThat(Positions.getOffset(SCRIPT, new Position(2, 0))).isEqualTo(38);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        0 | 99 | 10
        1 | 99 | 37
        2 | 99 | 39
        3 |  0 | 39
        9 |  9 | 39
       -1 | -1 |  0
        """)
    void clampOutOfRangePositions(int line, int character, int offset) {
        assertThat(Positions.getOffset(SCRIPT, new Position(line, character))).isEqualTo(offset);
    }

    @Test
    void supportCrlfAndCrLineTerminators() {
        assertThat(Positions.getOffset("a\r\nb\rc\nd", new Position(1, 0))).isEqualTo(3);
        assertThat(Positions.getOffset("a\r\nb\rc\nd", new Position(2, 0))).isEqualTo(5);
        assertThat(Positions.getOffset("a\r\nb\rc\nd", new Position(3, 0))).isEqualTo(7);
        assertThat(Positions.getOffset("a\r\nb\rc\nd", new Position(0, 5))).isEqualTo(1);
    }

    @Test
    void convertOffsetToPosition() {
        assertThat(Positions.getPosition(SCRIPT, 0)).isEqualTo(new Position(0, 0));
        assertThat(Positions.getPosition(SCRIPT, 11)).isEqualTo(new Position(1, 0));
        assertThat(Positions.getPosition(SCRIPT, 38)).isEqualTo(new Position(2, 0));
        assertThat(Positions.getPosition(SCRIPT, 50)).isEqualTo(new Position(-1, -1));
    }

}
