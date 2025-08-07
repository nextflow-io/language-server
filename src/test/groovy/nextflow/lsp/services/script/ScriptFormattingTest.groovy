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

package nextflow.lsp.services.script

import nextflow.script.formatter.FormattingOptions
import spock.lang.Specification
import com.github.difflib.text.*;
import java.util.stream.Collectors;

import static nextflow.lsp.TestUtils.*

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */



class ScriptFormattingTest extends Specification {

    enum Color {
        RESET("\033[0m"),       // Reset
        RED("\033[1;31m"),      // Red
        GREEN("\033[1;32m"),    // Green

        BLACK("\033[1;30m");   // BLACK

        private final String code;

        Color(String code) {
            this.code = code;
        }

        @Override
        public String toString() {
            return code;
        }
    }

    boolean checkFormat(ScriptService service, String uri, String before, String after) {
        open(service, uri, before)
        def textEdits = service.formatting(URI.create(uri), new FormattingOptions(4, true))
        def actualText
        if (textEdits.size() > 0) {
            actualText = textEdits.first().getNewText()
        } else {
            actualText = "No text at all"
        }
        def expectedText = after.stripIndent()
        System.err.println("Input text:\n" + before)
        // Create a configured DiffRowGenerator
        DiffRowGenerator generator = DiffRowGenerator.create()
                        //  .showInlineDiffs(true)
                        // // .mergeOriginalRevised(true)
                        //  .inlineDiffByWord(true)
                        // .oldTag(f -> Color.RED.toString())      //introduce markdown style for strikethrough
                        // .newTag(f -> Color.GREEN.toString())     //introduce markdown style for bold
                        .build();

        // Compute the differences for two test texts.
        List<DiffRow> rows = generator.generateDiffRows(
            expectedText.lines().collect(Collectors.toList()),
            actualText.lines().collect(Collectors.toList())
        );
        boolean success  = actualText == expectedText

        System.err.println("\nDiff comparision between expected and actual");
        for (DiffRow row : rows) {
            DiffRow.Tag tag = row.getTag();
            if (tag ==  DiffRow.Tag.INSERT) {
                System.err.println(Color.GREEN.toString() + "++: " + row.getNewLine() + Color.RESET.toString());
            } else if (tag ==  DiffRow.Tag.DELETE) {
                System.err.println(Color.RED.toString() + "--: " + row.getOldLine() + Color.RESET.toString());
            } else if (tag ==  DiffRow.Tag.CHANGE) {
                System.err.println(Color.GREEN.toString() + "++: " + row.getNewLine() + Color.RESET.toString());
                System.err.println(Color.RED.toString() + "--: " + row.getOldLine() + Color.RESET.toString());
            } else {
                System.err.println(Color.BLACK.toString() + "  : " + row.getOldLine() + Color.RESET.toString());
            }
        }
        if (rows.stream().allMatch(r -> r.getTag() == DiffRow.Tag.EQUAL)) {
            System.err.println(String.format("No diff! Comparison yields %s%b%s\n",
                (success ? Color.GREEN : Color.RED), success, Color.RESET)
            );
        }
        return actualText == expectedText
    }

    def 'should format leading comment' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkFormat(service, uri,
            '''\
            workflow { println 'Hello!' }
            ''',
            '''\
            workflow {
                println('Hello!')
            }
            '''
        )
        checkFormat(service, uri, 
            '''\
            workflow {
                println('Hello!')
            }
            ''',
            '''\
            workflow {
                println('Hello!')
            }
            '''
        )
    }

    def 'should format an include declaration' () {
        given:
        def service = getScriptService()
        def uri = getUri('main.nf')

        expect:
        checkFormat(service, uri,
            '''\
            include{foo;bar}from'./foobar.nf'
            ''',
            '''\
            include { foo ; bar } from './foobar.nf'
            '''
        )
        checkFormat(service, uri,
            '''\
            include{
            foo;bar
            }from'./foobar.nf'
            ''',
            '''\
            include {
                foo ;
                bar
            } from './foobar.nf'
            '''
        )
    }

}
