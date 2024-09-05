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
    @Constant('''
        Alias of `workflow.projectDir`.
    ''')
    Path baseDir

    @Constant('''
        Alias of `workflow.launchDir`.
    ''')
    Path launchDir

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
        Alias of `workflow.projectDir`.
    ''')
    Path projectDir

    @Constant('''
        Map of user-defined pipeline secrets.
    ''')
    Map secrets

    @Constant('''
        Alias of `workflow.workDir`.
    ''')
    Path workDir

    @Constant('''
        Map of workflow runtime information.
    ''')
    WorkflowMetadata workflow

    @Function('''
        Create a branch criteria to use with the `branch` operator.
    ''')
    void branchCriteria(Closure closure) {
    }

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
        Create a grouping key to use with the [groupTuple](https://nextflow.io/docs/latest/operator.html#grouptuple) operator.
    ''')
    GroupKey groupKey(Object key, int size) {
    }

    @Function('''
        Create a multi-map criteria to use with the `multiMap` operator.
    ''')
    void multiMapCriteria(Closure closure) {
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
