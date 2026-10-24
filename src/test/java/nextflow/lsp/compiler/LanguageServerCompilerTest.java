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

import java.io.IOException;
import java.nio.file.Files;

import groovy.lang.GroovyClassLoader;
import nextflow.lsp.TestWorkspace;
import nextflow.script.control.LazyErrorCollector;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@ExtendWith(TestWorkspace.class)
class LanguageServerCompilerTest {

    private final LanguageServerCompiler compiler = new LanguageServerCompiler(new CompilerConfiguration(), new GroovyClassLoader());

    private static String sourceText(SourceUnit unit) throws IOException {
        try( var reader = unit.getSource().getReader() ) {
            var builder = new StringBuilder();
            int c;
            while( (c = reader.read()) != -1 )
                builder.append((char) c);
            return builder.toString();
        }
    }

    @Test
    void createSourceUnitFromText() throws IOException {
        var file = TestWorkspace.root().resolve("main.nf");
        Files.writeString(file, "on disk");

        var unit = compiler.createSourceUnit(file.toUri(), "in memory");
        assertThat(sourceText(unit)).isEqualTo("in memory");
        assertThat(unit.getSource().getURI()).isEqualTo(file.toUri());
        assertThat(unit.getErrorCollector()).isInstanceOf(LazyErrorCollector.class);
    }

    @Test
    void createSourceUnitFromTextForFileThatIsNotOnDisk() throws IOException {
        var uri = TestWorkspace.root().resolve("unsaved.nf").toUri();

        var unit = compiler.createSourceUnit(uri, "unsaved");
        assertThat(sourceText(unit)).isEqualTo("unsaved");
    }

}
