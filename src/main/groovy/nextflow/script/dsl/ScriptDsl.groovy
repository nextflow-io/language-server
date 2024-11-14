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
import nextflow.extension.GroupKey
import nextflow.script.WorkflowMetadata
import nextflow.util.ArrayTuple
import org.codehaus.groovy.ast.ClassNode
import org.slf4j.Logger

@CompileStatic
class ScriptDsl implements DslScope {

    public static final List<ClassNode> TYPES = [
        new ClassNode(java.nio.file.Path),
        new ClassNode(nextflow.script.types.Channel),
        new ClassNode(nextflow.util.Duration),
        new ClassNode(nextflow.util.MemoryUnit),
    ]

    @Deprecated
    @Description('''
        Alias of `workflow.projectDir`.
    ''')
    Path baseDir

    @Description('''
        Alias of `workflow.launchDir`.
    ''')
    Path launchDir

    @Description('''
        Logger which can be used to log messages to the console.
    ''')
    Logger log

    @Description('''
        The directory where a module script is located (equivalent to `projectDir` if used in the main script).
    ''')
    Path moduleDir

    @Description('''
        Map of Nextflow runtime information.
    ''')
    NextflowMeta nextflow

    @Description('''
        Alias of `workflow.projectDir`.
    ''')
    Path projectDir

    @Description('''
        Map of user-defined pipeline secrets.
    ''')
    Map secrets

    @Description('''
        Alias of `workflow.workDir`.
    ''')
    Path workDir

    @Description('''
        Map of workflow runtime information.
    ''')
    WorkflowMetadata workflow

    @Description('''
        Create a branch criteria to use with the `branch` operator.
    ''')
    void branchCriteria(Closure closure) {
    }

    @Description('''
        Throw a script runtime error with an optional error message.
    ''')
    void error(String message=null) {
    }

    @Deprecated
    @Description('''
        Stop the pipeline execution and return an exit code and optional error message.
    ''')
    void exit(int exitCode=0, String message=null) {
    }

    @Description('''
        Get one or more files from a path or glob pattern. Returns a Path or list of Paths if there are multiple files.
    ''')
    /* Path | List<Path> */
    Object file(Map opts=null, String filePattern) {
    }

    @Description('''
        Convenience method for `file()` that always returns a list.
    ''')
    List<Path> files(Map opts=null, String filePattern) {
    }

    @Description('''
        Create a grouping key to use with the [groupTuple](https://nextflow.io/docs/latest/operator.html#grouptuple) operator.
    ''')
    GroupKey groupKey(Object key, int size) {
    }

    @Description('''
        Create a multi-map criteria to use with the `multiMap` operator.
    ''')
    void multiMapCriteria(Closure closure) {
    }

    @Description('''
        Print a value to standard output.
    ''')
    void print(Object object) {
    }

    @Description('''
        Print a newline to standard output.
    ''')
    void println() {
    }

    @Description('''
        Print a value to standard output with a newline.
    ''')
    void println(Object object) {
    }

    @Description('''
        Send an email.
    ''')
    void sendMail(Map params) {
    }

    @Description('''
        Create a tuple object from the given arguments.
    ''')
    ArrayTuple tuple(Object... args) {
    }

}
