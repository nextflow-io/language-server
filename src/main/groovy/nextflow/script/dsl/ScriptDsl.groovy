/*
 * Copyright 2013-2024, Seqera Labs
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
package nextflow.script.dsl

import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.NextflowMeta
import nextflow.script.WorkflowMetadata
import nextflow.util.ArrayTuple
import org.codehaus.groovy.ast.ClassNode
import org.slf4j.Logger

@CompileStatic
class ScriptDsl implements DslScope {

    static final List<ClassNode> TYPES = [
        java.nio.file.Path,
        nextflow.Channel,
        nextflow.util.Duration,
        nextflow.util.MemoryUnit,
    ].collect { clazz -> new ClassNode(clazz) }

    @Constant('''
        Logger which can be used to log messages to the console.
    ''')
    Logger log

    @Constant('''
        The directory where a module script is located (equivalent to `projectDir` if used in the main script).
    ''')
    Path moduleDir

    @Constant('''
        Map of Nextflow runtime information.
    ''')
    NextflowMeta nextflow

    @Constant('''
        Map of user-defined pipeline secrets.
    ''')
    Map secrets

    @Constant('''
        Map of workflow runtime information.
    ''')
    WorkflowMetadata workflow

    @Function('''
        Throw a script runtime error with an optional error message.
    ''')
    void error(String message=null) {
    }

    @Function('''
        Get one or more files from a path or glob pattern. Returns a Path or list of Paths if there are multiple files.
    ''')
    /* Path | List<Path> */
    Object file(Map opts=null, String filePattern) {
    }

    @Function('''
        Convenience method for `file()` that always returns a list.
    ''')
    List<Path> files(Map opts=null, String filePattern) {
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
    void sendMail(Map params) {
    }

    @Function('''
        Create a tuple object from the given arguments.
    ''')
    ArrayTuple tuple(Object... args) {
    }

}
