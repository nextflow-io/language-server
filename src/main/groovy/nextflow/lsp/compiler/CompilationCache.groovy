package nextflow.lsp.compiler

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import groovy.lang.GroovyClassLoader
import groovy.transform.CompileStatic
import nextflow.lsp.file.FileCache
import nextflow.lsp.util.Logger
import org.antlr.v4.runtime.RecognitionException
import org.codehaus.groovy.GroovyBugError
import org.codehaus.groovy.ast.CompileUnit
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException

/**
 * Cache compiled sources and defer compilation errors to the
 * language server.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
abstract class CompilationCache extends CompilationUnit {

    private static Logger log = Logger.instance

    private Path workspaceRoot

    private FileCache fileCache

    abstract protected String getFileExtension()

    CompilationCache(CompilerConfiguration config, GroovyClassLoader loader) {
        super(config, null, loader)
        this.@errorCollector = new LanguageServerErrorCollector(config)
    }

    void setErrorCollector(LanguageServerErrorCollector errorCollector) {
        this.@errorCollector = errorCollector
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
        addSources()
    }

    /**
     * Update the cache with source files from the current workspace.
     */
    void update() {
        // invalidate changed files
        final changedUris = fileCache.getChangedFiles()
        final changedSources = sources.values().findAll { sourceUnit ->
            changedUris.contains( sourceUnit.getSource().getURI() )
        }

        for( final sourceUnit : changedSources ) {
            // remove class definitions from changed source files
            if( sourceUnit.getAST() != null ) {
                final changedClasses = sourceUnit.getAST().getClasses().collect { classNode -> classNode.getName() }
                classes.removeIf(clazz -> changedClasses.contains(clazz.getName()))
            }

            sources.remove(sourceUnit.getName())
        }

        // remove modules from changed source files
        final modules = ast.getModules()
        ast = new CompileUnit(classLoader, null, configuration)
        for( final module : modules ) {
            if( !changedSources.contains(module.getContext()) )
                ast.addModule(module)
        }

        // reset errors
        ((LanguageServerErrorCollector) errorCollector).clear()

        // add changed source files
        addSources(changedUris)
    }

    /**
     * Add source files to the cache.
     *
     * If the set of changed files is provided, only those files
     * are added. Otherwise, all workspace source files are added.
     *
     * @param changedUris
     */
    protected void addSources(Set<URI> changedUris = null) {
        if( workspaceRoot != null ) {
            // add files from the workspace
            addSourcesFromWorkspace(changedUris)
        }
        else {
            // add open files from file cache
            for( final uri : fileCache.getOpenFiles() ) {
                if( changedUris != null && !changedUris.contains(uri) )
                    continue
                addSourceFromFileCache(uri)
            }
        }
    }

    /**
     * Add sources files from the current workspace.
     *
     * If the set of changed files is provided, only those files
     * are added. Otherwise, all available files are added.
     *
     * @param changedUris
     */
    protected void addSourcesFromWorkspace(Set<URI> changedUris) {
        try {
            if( !Files.exists(workspaceRoot) )
                return

            for( final filePath : Files.walk(workspaceRoot) ) {
                if( !filePath.toString().endsWith(getFileExtension()) )
                    continue

                final fileUri = filePath.toUri()
                if( changedUris != null && !changedUris.contains(fileUri) )
                    continue

                final file = filePath.toFile()
                if( fileCache.isOpen(fileUri) )
                    addSourceFromFileCache(fileUri)
                else if( file.isFile() )
                    addSource(file)
            }
        }
        catch( IOException e ) {
            log.error "Failed to add files from workspace: ${workspaceRoot}"
        }
    }

    /**
     * Add a source file from the file cache.
     *
     * @param uri
     */
    protected void addSourceFromFileCache(URI uri) {
        final contents = fileCache.getContents(uri)
        final filePath = Paths.get(uri)
        final sourceUnit = new SourceUnit(
                filePath.toString(),
                new StringReaderSourceWithURI(contents, uri, getConfiguration()),
                getConfiguration(),
                getClassLoader(),
                getErrorCollector())
        addSource(sourceUnit)
    }

    /**
     * Compile all source files enough to build the AST, deferring any errors
     * to be handled later.
     *
     * See: http://groovy-lang.org/metaprogramming.html#_compilation_phases_guide
     */
    @Override
    void compile() {
        try {
            compile(Phases.CANONICALIZATION)
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
    }

    /**
     * Get the list of errors from the previous compilation.
     */
    List<SyntaxException> getErrors() {
        final messages = getErrorCollector().getErrors()
        if( !messages )
            return Collections.emptyList()

        final List<SyntaxException> result = []
        for( final message : messages ) {
            if( message instanceof SyntaxErrorMessage )
                result << message.cause
        }

        return result
    }

}
