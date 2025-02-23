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
package nextflow.lsp.services.config;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import nextflow.config.formatter.ConfigFormattingVisitor;
import nextflow.lsp.services.FormattingProvider;
import nextflow.lsp.util.Logger;
import nextflow.lsp.util.Positions;
import nextflow.script.formatter.FormattingOptions;
import org.codehaus.groovy.runtime.IOGroovyMethods;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

/**
 * Provide formatting for a config file.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigFormattingProvider implements FormattingProvider {

    private static Logger log = Logger.getInstance();

    private ConfigAstCache ast;

    public ConfigFormattingProvider(ConfigAstCache ast) {
        this.ast = ast;
    }

    @Override
    public List<? extends TextEdit> formatting(URI uri, FormattingOptions options) {
        if( ast == null ) {
            log.error("ast cache is empty while providing formatting");
            return Collections.emptyList();
        }

        if( !ast.hasAST(uri) || ast.hasSyntaxErrors(uri) )
            return Collections.emptyList();

        var sourceUnit = ast.getSourceUnit(uri);
        String oldText;
        try {
            oldText = IOGroovyMethods.getText(sourceUnit.getSource().getReader());
        }
        catch( IOException e ) {
            return Collections.emptyList();
        }

        var range = new Range(new Position(0, 0), Positions.getPosition(oldText, oldText.length()));
        var visitor = new ConfigFormattingVisitor(sourceUnit, options);
        visitor.visit();
        var newText = visitor.toString();

        return List.of( new TextEdit(range, newText) );
    }

}
