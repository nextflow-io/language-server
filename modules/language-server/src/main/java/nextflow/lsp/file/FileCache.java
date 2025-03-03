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
package nextflow.lsp.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import nextflow.lsp.util.Positions;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;

/**
 * Cache the contents of open files and track changed files
 * (files that need to be recompiled).
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class FileCache {

    private Map<URI, String> openFiles = new HashMap<>();
    private Set<URI> changedFiles = new HashSet<>();

    public Set<URI> getOpenFiles() {
        return openFiles.keySet();
    }

    public Set<URI> removeChangedFiles() {
        var result = changedFiles;
        changedFiles = new HashSet<>();
        return result;
    }

    public void markChanged(String filename) {
        changedFiles.add(URI.create(filename));
    }

    public void markChanged(URI uri) {
        changedFiles.add(uri);
    }

    public boolean isOpen(URI uri) {
        return openFiles.containsKey(uri);
    }

    /**
     * When a file is opened, add it to the cache and mark it as
     * changed.
     *
     * @param params
     */
    public void didOpen(DidOpenTextDocumentParams params) {
        var uri = URI.create(params.getTextDocument().getUri());
        openFiles.put(uri, params.getTextDocument().getText());
        changedFiles.add(uri);
    }

    /**
     * When a file is changed, update the file contents in the cache
     * and mark it as changed.
     *
     * @param params
     */
    public void didChange(DidChangeTextDocumentParams params) {
        var uri = URI.create(params.getTextDocument().getUri());
        var oldText = openFiles.get(uri);
        var firstChange = params.getContentChanges().get(0);
        if( firstChange.getRange() == null ) {
            // update entire file contents
            openFiles.put(uri, firstChange.getText());
        }
        else {
            // update file contents with incremental changes
            var sortedChanges = new ArrayList<>(params.getContentChanges());
            sortedChanges.sort((a, b) -> {
                return Positions.COMPARATOR.compare( a.getRange().getStart(), b.getRange().getStart() );
            });
            var builder = new StringBuilder();
            int previousEnd = 0;
            for( var change : sortedChanges ) {
                var range = change.getRange();
                var offsetStart = Positions.getOffset(oldText, range.getStart());
                var offsetEnd = Positions.getOffset(oldText, range.getEnd());
                builder.append(oldText.substring(previousEnd, offsetStart));
                builder.append(change.getText());
                previousEnd = offsetEnd;
            }
            builder.append(oldText.substring(previousEnd));
            openFiles.put(uri, builder.toString());
        }
        changedFiles.add(uri);
    }

    /**
     * When a file is closed, remove it from the cache and
     * mark it as changed.
     *
     * @param params
     */
    public void didClose(DidCloseTextDocumentParams params) {
        var uri = URI.create(params.getTextDocument().getUri());
        openFiles.remove(uri);
        changedFiles.add(uri);
    }

    /**
     * Get the contents of a file from the cache, or from the
     * filesystem if the file is not open.
     *
     * @param uri
     */
    public String getContents(URI uri) {
        if( !openFiles.containsKey(uri) ) {
            BufferedReader reader = null;
            try {
                reader = Files.newBufferedReader(Paths.get(uri));
                var builder = new StringBuilder();
                int next = -1;
                while( (next = reader.read()) != -1 )
                    builder.append((char) next);
                return builder.toString();
            }
            catch( IOException e ) {
                return null;
            }
            finally {
                if( reader != null ) {
                    try {
                        reader.close();
                    }
                    catch( IOException e ) {
                    }
                }
            }
        }
        return openFiles.get(uri);
    }

    public void setContents(URI uri, String contents) {
        openFiles.put(uri, contents);
    }

}
