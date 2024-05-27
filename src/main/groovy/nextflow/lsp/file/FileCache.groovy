package nextflow.lsp.file

import groovy.transform.CompileStatic
import nextflow.lsp.util.Positions
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams

import java.nio.file.Files
import java.nio.file.Paths

/**
 * Cache the contents of open files and track changed files
 * (files that need to be recompiled).
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class FileCache {

    private Map<URI, String> openFiles = [:]
    private Set<URI> changedFiles = []

    Set<URI> getOpenFiles() {
        return openFiles.keySet()
    }

    Set<URI> removeChangedFiles() {
        final result = changedFiles
        changedFiles = new HashSet<>()
        return result
    }

    void markChanged(String filename) {
        changedFiles.add(URI.create(filename))
    }

    void markChanged(URI uri) {
        changedFiles.add(uri)
    }

    boolean isOpen(URI uri) {
        return openFiles.containsKey(uri)
    }

    /**
     * When a file is opened, add it to the cache and mark it as
     * changed.
     *
     * @param params
     */
    void didOpen(DidOpenTextDocumentParams params) {
        final uri = URI.create(params.getTextDocument().getUri())
        openFiles.put(uri, params.getTextDocument().getText())
        changedFiles.add(uri)
    }

    /**
     * When a file is changed, update the file contents in the cache
     * and mark it as changed.
     *
     * @param params
     */
    void didChange(DidChangeTextDocumentParams params) {
        final uri = URI.create(params.getTextDocument().getUri())
        final oldText = openFiles.get(uri)
        final firstChange = params.getContentChanges().first()
        if( firstChange.range == null ) {
            // update entire file contents
            openFiles.put(uri, firstChange.text)
        }
        else {
            // update file contents with incremental changes
            final sortedChanges = params.getContentChanges().sort { a, b ->
                Positions.COMPARATOR.compare( a.range.start, b.range.start )
            }
            final builder = new StringBuilder()
            int previousEnd = 0
            for( final change : sortedChanges ) {
                final range = change.range
                final offsetStart = Positions.getOffset(oldText, range.start)
                final offsetEnd = Positions.getOffset(oldText, range.end)
                builder.append(oldText.substring(previousEnd, offsetStart))
                builder.append(change.text)
                previousEnd = offsetEnd
            }
            builder.append(oldText.substring(previousEnd))
            openFiles.put(uri, builder.toString())
        }
        changedFiles.add(uri)
    }

    /**
     * When a file is closed, remove it from the cache and
     * mark it as changed.
     *
     * @param params
     */
    void didClose(DidCloseTextDocumentParams params) {
        final uri = URI.create(params.getTextDocument().getUri())
        openFiles.remove(uri)
        changedFiles.add(uri)
    }

    /**
     * Get the contents of a file from the cache, or from the
     * filesystem if the file is not open.
     *
     * @param uri
     */
    String getContents(URI uri) {
        if( !openFiles.containsKey(uri) ) {
            BufferedReader reader = null
            try {
                reader = Files.newBufferedReader(Paths.get(uri))
                final builder = new StringBuilder()
                int next = -1
                while( (next = reader.read()) != -1 )
                    builder.append((char) next)
                return builder.toString()
            }
            catch( IOException e ) {
                return null
            }
            finally {
                if( reader != null ) {
                    try {
                        reader.close()
                    }
                    catch( IOException e ) {
                    }
                }
            }
        }
        return openFiles.get(uri)
    }

    void setContents(URI uri, String contents) {
        openFiles.put(uri, contents)
    }

}
