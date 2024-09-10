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
import java.util.regex.Pattern;

import nextflow.config.v2.ConfigAppendNode;
import nextflow.config.v2.ConfigAssignNode;
import nextflow.config.v2.ConfigBlockNode;
import nextflow.config.v2.ConfigIncludeNode;
import nextflow.config.v2.ConfigNode;
import nextflow.config.v2.ConfigVisitorSupport;
import nextflow.lsp.ast.ASTNodeCache;
import nextflow.lsp.services.util.CustomFormattingOptions;
import nextflow.lsp.services.util.Formatter;
import nextflow.lsp.services.FormattingProvider;
import nextflow.lsp.util.Logger;
import nextflow.lsp.util.Positions;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
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

    private ASTNodeCache ast;

    public ConfigFormattingProvider(ASTNodeCache ast) {
        this.ast = ast;
    }

    @Override
    public List<? extends TextEdit> formatting(URI uri, CustomFormattingOptions options) {
        if( ast == null ) {
            log.error("ast cache is empty while providing formatting");
            return Collections.emptyList();
        }

        if( !ast.hasAST(uri) || ast.hasErrors(uri) )
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
        var visitor = new Visitor(sourceUnit, options);
        visitor.visit();
        var newText = visitor.toString();

        return List.of( new TextEdit(range, newText) );
    }

    private static class Visitor extends ConfigVisitorSupport {

        private SourceUnit sourceUnit;

        private CustomFormattingOptions options;

        private Formatter fmt;

        public Visitor(SourceUnit sourceUnit, CustomFormattingOptions options) {
            this.sourceUnit = sourceUnit;
            this.options = options;
            this.fmt = new Formatter(options);
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }

        public void visit() {
            var moduleNode = sourceUnit.getAST();
            if( moduleNode instanceof ConfigNode cn )
                super.visit(cn);
        }

        public String toString() {
            return fmt.toString();
        }

        // config statements

        @Override
        public void visitConfigAssign(ConfigAssignNode node) {
            fmt.appendComments(node);
            fmt.appendIndent();
            var name = DefaultGroovyMethods.join(node.names, ".");
            fmt.append(name);
            if( currentAlignmentWidth > 0 ) {
                var padding = currentAlignmentWidth - name.length();
                fmt.append(" ".repeat(padding));
            }
            fmt.append(node instanceof ConfigAppendNode ? " " : " = ");
            fmt.visit(node.value);
            fmt.appendNewLine();
        }

        private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_]+[a-zA-Z0-9_]*");

        private int currentAlignmentWidth = 0;

        @Override
        public void visitConfigBlock(ConfigBlockNode node) {
            fmt.appendComments(node);
            fmt.appendIndent();
            if( node.kind != null ) {
                fmt.append(node.kind);
                fmt.append(": ");
            }
            var name = node.name;
            if( IDENTIFIER.matcher(name).matches() ) {
                fmt.append(name);
            }
            else {
                fmt.append('\'');
                fmt.append(name);
                fmt.append('\'');
            }
            fmt.append(" {");
            fmt.appendNewLine();

            int caw = currentAlignmentWidth;
            if( options.harshilAlignment() ) {
                int maxWidth = 0;
                for( var stmt : node.statements ) {
                    if( stmt instanceof ConfigAssignNode can ) {
                        var width = DefaultGroovyMethods.join(can.names, ".").length();
                        if( maxWidth < width )
                            maxWidth = width;
                    }
                }
                currentAlignmentWidth = maxWidth;
            }

            fmt.incIndent();
            super.visitConfigBlock(node);
            fmt.decIndent();

            if( options.harshilAlignment() )
                currentAlignmentWidth = caw;

            fmt.appendIndent();
            fmt.append('}');
            fmt.appendNewLine();
        }

        @Override
        public void visitConfigInclude(ConfigIncludeNode node) {
            fmt.appendComments(node);
            fmt.appendIndent();
            fmt.append("includeConfig ");
            fmt.visit(node.source);
            fmt.appendNewLine();
        }

    }

}
