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
package nextflow.script.dsl;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import groovy.lang.Closure;
import nextflow.script.types.NextflowMetadata;
import nextflow.script.types.WorkflowMetadata;
import org.slf4j.Logger;

public interface ScriptDsl extends DslScope {

    @Deprecated
    @Constant("baseDir")
    @Description("""
        Alias of `workflow.projectDir`.
    """)
    Path getBaseDir();

    @Constant("launchDir")
    @Description("""
        Alias of `workflow.launchDir`.
    """)
    Path getLaunchDir();

    @Constant("log")
    @Description("""
        Logger which can be used to log messages to the console.
    """)
    Logger getLog();

    @Constant("moduleDir")
    @Description("""
        The directory where a module script is located (equivalent to `projectDir` if used in the main script).
    """)
    Path getModuleDir();

    @Constant("nextflow")
    @Description("""
        Map of Nextflow runtime information.
    """)
    NextflowMetadata getNextflow();

    @Constant("projectDir")
    @Description("""
        Alias of `workflow.projectDir`.
    """)
    Path getProjectDir();

    @Constant("secrets")
    @Description("""
        Map of user-defined pipeline secrets.
    """)
    Map<String,?> getSecrets();

    @Constant("workDir")
    @Description("""
        Alias of `workflow.workDir`.
    """)
    Path getWorkDir();

    @Constant("workflow")
    @Description("""
        Map of workflow runtime information.
    """)
    WorkflowMetadata getWorkflow();

    @Description("""
        Create a branch criteria to use with the `branch` operator.
    """)
    Object branchCriteria(Closure closure);

    @Description("""
        Throw a script runtime error with an optional error message.
    """)
    void error(String message);

    @Deprecated
    @Description("""
        Stop the pipeline execution and return an exit code and optional error message.
    """)
    void exit(int exitCode, String message);

    @Description("""
        Get one or more files from a path or glob pattern. Returns a Path or list of Paths if there are multiple files.
    """)
    /* Path | Collection<Path> */
    Object file(Map<String,?> opts, String filePattern);

    @Description("""
        Convenience method for `file()` that always returns a list.
    """)
    Collection<Path> files(Map<String,?> opts, String filePattern);

    @Description("""
        Create a grouping key to use with the [groupTuple](https://nextflow.io/docs/latest/operator.html#grouptuple) operator.
    """)
    Object groupKey(Object key, int size);

    @Description("""
        Create a multi-map criteria to use with the `multiMap` operator.
    """)
    Object multiMapCriteria(Closure closure);

    @Description("""
        Print a value to standard output.
    """)
    void print(Object object);

    @Description("""
        Print a newline to standard output.
    """)
    void println();

    @Description("""
        Print a value to standard output with a newline.
    """)
    void println(Object object);

    @Description("""
        Send an email.
    """)
    void sendMail(Map<String,?> params);

    @Description("""
        Create a tuple object from the given arguments.
    """)
    List<?> tuple(Object... args);

}