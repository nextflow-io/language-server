package nextflow.lsp.compiler

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import groovy.lang.GroovyClassLoader
import groovy.transform.CompileStatic
import nextflow.lsp.file.FileCache
import nextflow.lsp.util.Logger
import org.antlr.v4.runtime.InputMismatchException
import org.antlr.v4.runtime.NoViableAltException
import org.codehaus.groovy.GroovyBugError
import org.codehaus.groovy.ast.CompileUnit
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit

/**
 * Cache compiled sources and defer compilation errors to the
 * language server.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
abstract class CompilationCache extends CompilationUnit {

    private static Logger log = Logger.instance

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
        addSources(workspaceRoot, fileCache, null)
    }

    /**
     * Update the cache with source files from the current workspace.
     *
     * @param workspaceRoot
     * @param fileCache
     */
    void update(Path workspaceRoot, FileCache fileCache) {
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
        addSources(workspaceRoot, fileCache, changedUris)
    }

    /**
     * Add source files to the cache.
     *
     * If the set of changed files is provided, only those files
     * are added. Otherwise, all workspace source files are added.
     *
     * @param workspaceRoot
     * @param fileCache
     * @param changedUris
     */
    protected void addSources(Path workspaceRoot, FileCache fileCache, Set<URI> changedUris = null) {
        if( workspaceRoot != null ) {
            // add files from the workspace
            addSourcesFromDirectory(workspaceRoot, fileCache, changedUris)
        }
        else {
            // add open files from file cache
            for( final uri : fileCache.getOpenFiles() ) {
                if( changedUris != null && !changedUris.contains(uri) )
                    continue
                addSourceFromFileCache(uri, fileCache.getContents(uri))
            }
        }
    }

    /**
     * Add sources files from a directory tree.
     *
     * If the set of changed files is provided, only those files
     * are added. Otherwise, all available files are added.
     *
     * @param dirPath
     * @param fileCache
     * @param changedUris
     */
    protected void addSourcesFromDirectory(Path dirPath, FileCache fileCache, Set<URI> changedUris) {
        try {
            if( !Files.exists(dirPath) )
                return

            for( final filePath : Files.walk(dirPath) ) {
                if( !filePath.toString().endsWith(getFileExtension()) )
                    continue

                final fileUri = filePath.toUri()
                if( changedUris != null && !changedUris.contains(fileUri) )
                    continue

                final file = filePath.toFile()
                if( fileCache.isOpen(fileUri) )
                    addSourceFromFileCache(fileUri, fileCache.getContents(fileUri))
                else if( file.isFile() )
                    addSource(file)
            }
        }
        catch( IOException e ) {
            log.error "Failed to walk directory for source files: ${dirPath}"
        }
    }

    /**
     * Add a source file from the file cache.
     *
     * @param uri
     * @param contents
     */
    protected void addSourceFromFileCache(URI uri, String contents) {
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
        catch( InputMismatchException e ) {
        }
        catch( NoViableAltException e ) {
        }
        catch( CompilationFailedException e ) {
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

}
