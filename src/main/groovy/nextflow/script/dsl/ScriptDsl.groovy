package nextflow.script.dsl

import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.NextflowMeta
import nextflow.script.WorkflowMetadata
import nextflow.util.ArrayTuple
import org.codehaus.groovy.ast.ClassNode

@CompileStatic
class ScriptDsl {

    static final List<ClassNode> TYPES = [
        new ClassNode( java.nio.file.Path ),
        new ClassNode( nextflow.Channel ),
        new ClassNode( nextflow.util.Duration ),
        new ClassNode( nextflow.util.MemoryUnit ),
    ]

    @Constant('''
        The directory where a module script is located (equivalent to `projectDir` if used in the main script).
    ''')
    Path moduleDir

    @Constant('''
        Map of Nextflow runtime information.
    ''')
    NextflowMeta nextflow

    @Constant('''
        Map of workflow runtime information.
    ''')
    WorkflowMetadata workflow

    @Function('''
        Throw a script runtime error with an optional error message.
    ''')
    void error( String message=null ) {
    }

    @Function('''
        Get one or more files from a path or glob pattern. Returns a Path or list of Paths if there are multiple files.
    ''')
    /* Path | List<Path> */
    Object file( Map opts=null, String filePattern ) {
    }

    @Function('''
        Convenience method for `file()` that always returns a list.
    ''')
    List<Path> files( Map opts=null, String filePattern ) {
    }

    @Function('''
        Print a value to standard output.
    ''')
    void print(Object object) {
    }

    @Function('''
        Print a newline to standard output.
    ''')
    void println() {
    }

    @Function('''
        Print a value to standard output with a newline.
    ''')
    void println(Object object) {
    }

    @Function('''
        Send an email.
    ''')
    void sendMail( Map params ) {
    }

    @Function('''
        Create a tuple object from the given arguments.
    ''')
    ArrayTuple tuple( Object... args ) {
    }

}


import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface Constant {
    String value()
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface Function {
    String value()
}