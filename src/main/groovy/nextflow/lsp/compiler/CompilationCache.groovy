package nextflow.lsp.compiler

import java.nio.file.Files
import java.nio.file.Path

import groovy.lang.GroovyClassLoader
import groovy.transform.CompileStatic
import nextflow.lsp.file.FileCache
import nextflow.lsp.util.Logger
import org.antlr.v4.runtime.RecognitionException
import org.codehaus.groovy.GroovyBugError
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException

/**
 * Compile source files and defer compilation errors to the
 * language server.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class CompilationCache {

    private static Logger log = Logger.instance

    private String fileExtension

    private CompilerConfiguration config

    private GroovyClassLoader classLoader

    private LanguageServerErrorCollector errorCollector

    private Path workspaceRoot

    private FileCache fileCache

    private Map<URI, SourceUnit> sources = [:]

    private Map<URI, SourceUnit> queuedSources = [:]

    private Map<URI, List<SyntaxException>> errorsByURI = [:]

    CompilationCache(String fileExtension, CompilerConfiguration config, GroovyClassLoader classLoader) {
        this.fileExtension = fileExtension
        this.config = config
        this.classLoader = classLoader
        this.errorCollector = new LanguageServerErrorCollector(config)
    }

    /**
     * Initialize the cache with source files from the current workspace.
     *
     * @param workspaceRoot
     * @param fileCache
     */
    void initialize(Path workspaceRoot, FileCache fileCache) {
        this.workspaceRoot = workspaceRoot
        this.fileCache = fileCache
        if( workspaceRoot != null )
            addSourcesFromWorkspace()
        compile()
    }

    /**
     * Add all available sources files from the current workspace.
     */
    protected void addSourcesFromWorkspace() {
        try {
            if( !Files.exists(workspaceRoot) )
                return

            for( final path : Files.walk(workspaceRoot) ) {
                if( path.isDirectory() )
                    continue
                if( !path.toString().endsWith(fileExtension) )
                    continue

                addSource(path.toUri())
            }
        }
        catch( IOException e ) {
            log.error "Failed to add files from workspace: ${workspaceRoot}"
        }
    }

    /**
     * Update the cache for a set of source files.
     *
     * @param changedUris
     */
    void update(Set<URI> changedUris) {
        // invalidate changed files
        for( final uri : changedUris ) {
            sources.remove(uri)
            addSource(uri)
        }

        // re-compile changed source files
        compile()
    }

    /**
     * Add a source file from the file cache, or the filesystem
     * if it is not cached.
     *
     * @param uri
     */
    protected void addSource(URI uri) {
        if( fileCache.isOpen(uri) )
            addSourceFromFileCache(uri)
        else
            addSourceFromFileSystem(uri)
    }

    protected void addSourceFromFileCache(URI uri) {
        final contents = fileCache.getContents(uri)
        final sourceUnit = new SourceUnit(
                uri.toString(),
                new StringReaderSourceWithURI(contents, uri, config),
                config,
                classLoader,
                errorCollector)
        queuedSources.put(uri, sourceUnit)
    }

    protected void addSourceFromFileSystem(URI uri) {
        final sourceUnit = new SourceUnit(new File(uri), config, classLoader, errorCollector)
        queuedSources.put(uri, sourceUnit)
    }

    /**
     * Compile queued source files enough to build the AST, collecting
     * any errors to be handled later.
     */
    protected void compile() {
        queuedSources.each { uri, sourceUnit ->
            compile0(uri, sourceUnit)
        }
        queuedSources.clear()
    }

    protected void compile0(URI uri, SourceUnit sourceUnit) {
        // reset errors
        errorCollector.clear()
        errorsByURI.computeIfAbsent(uri, (key) -> []).clear()

        // compile source file
        try {
            sourceUnit.parse()
            sourceUnit.buildAST()
        }
        catch( RecognitionException e ) {
        }
        catch( GroovyBugError e ) {
            log.error 'Unexpected exception while compiling source files'
            e.printStackTrace(System.err)
        }
        catch( Exception e ) {
            log.error 'Unexpected exception while compiling source files'
            e.printStackTrace(System.err)
        }

        // update sources
        sources[uri] = sourceUnit

        // update errors
        for( final message : errorCollector.getErrors() ) {
            if( message instanceof SyntaxErrorMessage )
                errorsByURI[uri].add(message.cause)
        }
    }

    /**
     * Get the compiled unit for each source file.
     */
    Map<URI, SourceUnit> getSources() {
        return sources
    }

    /**
     * Get the list of current errors for each source file.
     */
    Map<URI, List<SyntaxException>> getErrors() {
        return errorsByURI
    }

}
