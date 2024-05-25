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
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.SourceUnit

/**
 * Compile source files and defer any errors to the
 * language server.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class Compiler {

    private static Logger log = Logger.instance

    private CompilerConfiguration config

    private GroovyClassLoader classLoader

    Compiler(CompilerConfiguration config, GroovyClassLoader classLoader) {
        this.config = config
        this.classLoader = classLoader
    }

    /**
     * Compile a set of source files.
     *
     * @param uris
     * @param fileCache
     */
    Map<URI, SourceUnit> compile(Set<URI> uris, FileCache fileCache) {
        final stream = uris.parallelStream().map((uri) -> {
            final sourceUnit = getSourceUnit(uri, fileCache)
            if( sourceUnit )
                compile(sourceUnit)
            return new Tuple2<>(uri, sourceUnit)
        })

        final Map<URI, SourceUnit> result = [:]
        for( final tuple : stream ) {
            final uri = tuple.v1
            final sourceUnit = tuple.v2
            result[uri] = sourceUnit
        }
        return result
    }

    /**
     * Prepare a source file from the open cache, or the filesystem
     * if it is not open.
     *
     * If the file is not open and doesn't exist in the filesystem,
     * it has been renamed. Return null so that the file is removed
     * from any downstream caches.
     *
     * @param uri
     */
    protected SourceUnit getSourceUnit(URI uri, FileCache fileCache) {
        if( fileCache.isOpen(uri) ) {
            final contents = fileCache.getContents(uri)
            return new SourceUnit(
                    uri.toString(),
                    new StringReaderSourceWithURI(contents, uri, config),
                    config,
                    classLoader,
                    newErrorCollector())
        }
        else if( Files.exists(Path.of(uri)) ) {
            return new SourceUnit(
                    new File(uri),
                    config,
                    classLoader,
                    newErrorCollector())
        }
        else
            return null
    }

    protected ErrorCollector newErrorCollector() {
        new LanguageServerErrorCollector(config)
    }

    /**
     * Compile a source file enough to build the AST, and defer
     * any errors to be handled later.
     *
     * @param sourceUnit
     */
    protected void compile(SourceUnit sourceUnit) {
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
    }

}
