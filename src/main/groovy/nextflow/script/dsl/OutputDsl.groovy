package nextflow.script.dsl

import groovy.transform.CompileStatic

@CompileStatic
class OutputDsl implements DslScope {

    @Constant('''
        List of positional arguments specified on the command line.
    ''')
    List<String> args

    @Constant('''
        Map of workflow parameters specified in the config file or as command line options.
    ''')
    Map<String,Object> params

    @Function('''
        Set the top-level output directory of the workflow. Defaults to the launch directory (`workflow.launchDir`).
    ''')
    void directory(String value) {
    }

    @Function('''
        *Currently only supported for S3.*

        Specify the media type a.k.a. [MIME type](https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_Types) of published files (default: `false`). Can be a string (e.g. `'text/html'`), or `true` to infer the content type from the file extension.
    ''')
    void contentType(value) {
    }

    @Function('''
        Enable or disable publishing (default: `true`).
    ''')
    void enabled(boolean value) {
    }

    @Function('''
        When `true`, the workflow will not fail if a file can't be published for some reason (default: `false`).
    ''')
    void ignoreErrors(boolean value) {
    }

    @Function('''
        The file publishing method (default: `'symlink'`).
    ''')
    void mode(String value) {
    }

    @Function('''
        When `true` any existing file in the specified folder will be overwritten (default: `'standard'`).
    ''')
    void overwrite(value) {
    }

    @Function('''
        *Currently only supported for S3.*

        Specify the storage class for published files.
    ''')
    void storageClass(String value) {
    }

    @Function('''
        *Currently only supported for S3.*

        Specify arbitrary tags for published files.
    ''')
    void tags(Map value) {
    }

    static class TargetDsl implements DslScope {

        @Function('''
            *Currently only supported for S3.*

            Specify the media type a.k.a. [MIME type](https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_Types) of published files (default: `false`). Can be a string (e.g. `'text/html'`), or `true` to infer the content type from the file extension.
        ''')
        void contentType(value) {
        }

        @Function('''
            Enable or disable publishing (default: `true`).
        ''')
        void enabled(boolean value) {
        }

        @Function('''
            When `true`, the workflow will not fail if a file can't be published for some reason (default: `false`).
        ''')
        void ignoreErrors(boolean value) {
        }

        @Function('''
            Create an index file of the values that were published.
        ''')
        void index(Closure closure) {
        }

        @Function('''
            The file publishing method (default: `'symlink'`).
        ''')
        void mode(String value) {
        }

        @Function('''
            When `true` any existing file in the specified folder will be overwritten (default: `'standard'`).
        ''')
        void overwrite(value) {
        }

        @Function('''
            Specify the publish path relative to the output directory (default: the target name).
        ''')
        void path(String value) {
        }

        @Function('''
            *Currently only supported for S3.*

            Specify the storage class for published files.
        ''')
        void storageClass(String value) {
        }

        @Function('''
            *Currently only supported for S3.*

            Specify arbitrary tags for published files.
        ''')
        void tags(Map value) {
        }

    }

    static class IndexDsl implements DslScope {

        @Function('''
            When `true`, the keys of the first record are used as the column names (default: `false`). Can also be a list of column names.
        ''')
        void header(value) {
        }

        @Function('''
            Closure which defines how to transform each published value into a CSV record. The closure should return a list or map. By default, no transformation is applied.
        ''')
        void mapper(Closure value) {
        }

        @Function('''
            The name of the index file relative to the target path (required).
        ''')
        void path(String value) {
        }

        @Function('''
            The character used to separate values (default: `','`).
        ''')
        void sep(String value) {
        }

    }

}
