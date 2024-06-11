package nextflow.script.dsl

import groovy.transform.CompileStatic

@CompileStatic
class ProcessDsl {

    @ProcessDirective('''
        The `clusterOptions` directive allows the usage of any native configuration option accepted by your cluster submit command. You can use it to request non-standard resources or use settings that are specific to your cluster and not supported out of the box by Nextflow.

        [Read more](https://nextflow.io/docs/latest/process.html#clusteroptions)
    ''')
    void clusterOptions(String value) {
    }

    @ProcessDirective('''
        The `conda` directive allows for the definition of the process dependencies using the [Conda](https://conda.io) package manager.

        [Read more](https://nextflow.io/docs/latest/process.html#conda)
    ''')
    void conda(String value) {
    }

    @ProcessDirective('''
        The `container` directive allows you to execute the process script in a container.

        [Read more](https://nextflow.io/docs/latest/process.html#container)
    ''')
    void container(String value) {
    }

    @ProcessDirective('''
        The `containerOptions` directive allows you to specify any container execution option supported by the underlying container engine (ie. Docker, Singularity, etc). This can be useful to provide container settings only for a specific process.

        [Read more](https://nextflow.io/docs/latest/process.html#containeroptions)
    ''')
    void containerOptions(String value) {
    }

    @ProcessDirective('''
        The `cpus` directive allows you to define the number of (logical) CPUs required by each task.

        [Read more](https://nextflow.io/docs/latest/process.html#cpus)
    ''')
    void cpus(value) {
    }

    @ProcessDirective('''
        The `debug` directive allows you to print the process standard output to Nextflow\'s standard output, i.e. the console. By default this directive is disabled.

        [Read more](https://nextflow.io/docs/latest/process.html#debug)
    ''')
    void debug(boolean value) {
    }

    @ProcessDirective('''
        The `errorStrategy` directive allows you to define how an error condition is managed by the process. By default when an error status is returned by the executed script, the process stops immediately. This in turn forces the entire pipeline to terminate.

        [Read more](https://nextflow.io/docs/latest/process.html#errorstrategy)
    ''')
    void errorStrategy(String value) {
    }

    @ProcessDirective('''
        The `label` directive allows the annotation of processes with a mnemonic identifier of your choice.

        [Read more](https://nextflow.io/docs/latest/process.html#label)
    ''')
    void label(String value) {
    }

    @ProcessDirective('''
        The `maxErrors` directive allows you to specify the maximum number of times a process can fail when using the `retry` or `ignore` error strategy. By default this directive is disabled.

        [Read more](https://nextflow.io/docs/latest/process.html#maxerrors)
    ''')
    void maxErrors(int value) {
    }

    @ProcessDirective('''
        The `maxRetries` directive allows you to define the maximum number of times a task can be retried when using the `retry` error strategy. By default only one retry is allowed.

        [Read more](https://nextflow.io/docs/latest/process.html#maxretries)
    ''')
    void maxRetries(int value) {
    }

    @ProcessDirective('''
        The `memory` directive allows you to define how much memory is required by each task. Can be a string (e.g. `\'8 GB\'`) or a memory unit (e.g. `8.GB`).

        [Read more](https://nextflow.io/docs/latest/process.html#memory)
    ''')
    void memory(value) {
    }

    @ProcessDirective('''
        The `tag` directive allows you to associate each process execution with a custom label, so that it will be easier to identify in the log file or in a report.

        [Read more](https://nextflow.io/docs/latest/process.html#tag)
    ''')
    void tag(String value) {
    }

}

class ProcessInputDsl {

    @ProcessInput('''
        ```groovy
        val <identifier>
        ```
        Declare a variable input. The received value can be any type, and it will be made available to the process body (i.e. `script`, `shell`, `exec`) as a variable with the given name.
    ''')
    void val(arg) {
    }

    @ProcessInput('''
        ```groovy
        path <identifier | stageName>
        ```
        Declare a file input. The received value should be a file or collection of files.

        The argument can be an identifier or string. If an identifier, the received value will be made available to the process body as a variable. If a string, the received value will be staged into the task directory under the given alias.
    ''')
    void path(arg) {
    }

    @ProcessInput('''
        ```groovy
        env <identifier>
        ```
        Declare an environment variable input. The received value should be a string, and it will be exported to the task environment as an environment variable given by `identifier`.
    ''')
    void env(arg) {
    }

    @ProcessInput('''
        ```groovy
        stdin
        ```
        Declare a `stdin` input. The received value should be a string, and it will be provided as the standard input (i.e. `stdin`) to the task script. It should be declared only once for a process.
    ''')
    void stdin() {
    }

    @ProcessInput('''
        ```groovy
        tuple <arg1>, <arg2>, ...
        ```
        Declare a tuple input. Each argument should be an input declaration such as `val`, `path`, `env`, or `stdin`.

        The received value should be a tuple with the same number of elements as the `tuple` declaration, and each received element should be compatible with the corresponding `tuple` argument. Each tuple element is treated the same way as if it were a standalone input.
    ''')
    void tuple(Object... args) {
    }

}

class ProcessOutputDsl {

    @ProcessOutput('''
        ```groovy
        val <value>
        ```
        Declare a value output. The argument can be any value, and it can reference any output variables defined in the process body (i.e. variables declared without the `def` keyword).
    ''')
    void val(arg) {
    }

    @ProcessOutput('''
        ```groovy
        path <pattern>
        ```
        Declare a file output. It receives the output files from the task environment that match the given pattern.
    ''')
    void path(arg) {
    }

    @ProcessOutput('''
        ```groovy
        env <identifier>
        ```
        Declare an environment variable output. It receives the value of the environment variable given by `identifier` from the task environment.
    ''')
    void env(arg) {
    }

    @ProcessOutput('''
        ```groovy
        stdout
        ```
        Declare a `stdout` output. It receives the standard output of the task script.
    ''')
    void stdout() {
    }

    @ProcessOutput('''
        ```groovy
        eval <command>
        ```
        Declare an `eval` output. It receives the standard output of the given command, which is executed in the task environment after the task script.
    ''')
    void eval(arg) {
    }

    @ProcessOutput('''
        ```groovy
        tuple <arg1>, <arg2>, ...
        ```
        Declare a tuple output. Each argument should be an output declaration such as `val`, `path`, `env`, `stdin`, or `eval`. Each tuple element is treated the same way as if it were a standalone output.
    ''')
    void tuple(Object... args) {
    }

}


import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface ProcessDirective {
    String value()
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface ProcessInput {
    String value()
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface ProcessOutput {
    String value()
}