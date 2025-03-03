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
package nextflow.lsp.services.script;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nextflow.lsp.services.LinkProvider;
import nextflow.lsp.util.Logger;
import nextflow.lsp.util.LanguageServerUtils;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/**
 * Provide the locations of links in a document.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ScriptLinkProvider implements LinkProvider {

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;

    public ScriptLinkProvider(ScriptAstCache ast) {
        this.ast = ast;
    }

    @Override
    public List<DocumentLink> documentLink(TextDocumentIdentifier textDocument) {
        if( ast == null ) {
            log.error("ast cache is empty while providing document links");
            return Collections.emptyList();
        }

        var uri = URI.create(textDocument.getUri());
        if( !ast.hasAST(uri) )
            return Collections.emptyList();

        var result = new ArrayList<DocumentLink>();
        for( var node : ast.getIncludeNodes(uri) ) {
            var source = node.source.getText();
            if( source.startsWith("plugin/") )
                continue;
            var range = LanguageServerUtils.astNodeToRange(node.source);
            var target = getIncludeUri(uri, source).toString();
            result.add(new DocumentLink(range, target));
        }

        return result;
    }

    protected static URI getIncludeUri(URI uri, String source) {
        Path includePath = Path.of(uri).getParent().resolve(source);
        if( Files.isDirectory(includePath) )
            includePath = includePath.resolve("main.nf");
        else if( !source.endsWith(".nf") )
            includePath = Path.of(includePath.toString() + ".nf");
        return includePath.normalize().toUri();
    }

}
