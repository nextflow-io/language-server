/*
 * Copyright 2024-2026, Seqera Labs
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
package nextflow

import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Type

import groovy.json.JsonGenerator
import groovy.json.JsonOutput
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ScopeName
import nextflow.config.spec.SpecNode
import nextflow.plugin.Plugins
import nextflow.script.dsl.Constant
import nextflow.script.dsl.Description
import nextflow.script.dsl.FeatureFlag
import nextflow.script.dsl.FeatureFlagDsl
import nextflow.script.dsl.FileOutputDsl
import nextflow.script.dsl.Namespace
import nextflow.script.dsl.Operator
import nextflow.script.dsl.ProcessDsl
import nextflow.script.dsl.ScriptDsl
import nextflow.script.dsl.Types
import nextflow.script.types.Channel
import nextflow.script.types.TaskConfig
import nextflow.script.types.Tuple
import nextflow.script.types.Value
import nextflow.script.types.VersionNumber

/**
 * Writes the API index: each name of the Nextflow language and config,
 * with its docs link, signature, summary, and type. See docs/api-index.md.
 */
class ApiIndexWriter {

    static final String DOCS = 'https://docs.seqera.io/nextflow/'

    static void main(String[] args) {
        if( args.length == 0 ) {
            System.err.println("Missing output path")
            System.exit(1)
        }
        final writer = new ApiIndexWriter()
        writer.script()
        writer.process()
        writer.config()
        final index = [
            apiIndex: 1,
            source: 'Nextflow reference',
            version: args.length > 1 && args[1] ? args[1] : null,
            base: DOCS,
            symbols: writer.symbols,
        ].findAll { k, v -> v != null }
        final json = new JsonGenerator.Options().disableUnicodeEscaping().build().toJson(index)
        final file = new File(args[0])
        file.parentFile.mkdirs()
        file.text = JsonOutput.prettyPrint(json) + '\n'
        println "Wrote ${writer.symbols.size()} symbols to $file"
    }

    final List<Map> symbols = []

    private void add(Map symbol) {
        symbols.add(symbol.findAll { k, v -> v != null && v != '' && v != [] })
    }

    // script

    void script() {
        for( final d : documented(ScriptDsl) ) {
            final type = d.method.returnType
            if( Namespace.isAssignableFrom(type) )
                namespace(d.name, type, "reference/stdlib-namespaces/${d.name}", d.text)
            else
                add(member(d, '', 'reference/stdlib-namespaces/global'))
        }

        for( final field : FeatureFlagDsl.declaredFields ) {
            final name = field.getAnnotation(FeatureFlag)?.value()
            if( name )
                add(name: name, kind: 'feature flag', href: "reference/feature-flags#${slug(name)}", summary: summary(description(field)))
        }

        for( final clazz : TYPES ) {
            final name = Types.getName(clazz)
            final href = "reference/stdlib-types/${kebab(name)}"
            final parents = clazz.interfaces.collect { Types.getName(it) }.findAll { it != name && it in TYPE_NAMES }
            add(name: name, kind: 'type', href: href, summary: summary(description(clazz)), extends: parents)
            for( final d : documented(clazz) ) {
                final operator = d.method.getAnnotation(Operator) != null
                if( operator && OPERATORS.containsKey(d.name) )
                    add(member(d, "${name}.", "reference/operator#${d.name.toLowerCase()}", 'operator'))
                else
                    add(member(d, "${name}.", href, operator ? 'operator' : null))
            }
        }

        // nf-lang defines only the operators of typed channels
        final defined = symbols.collect { it.name.toString() } as Set
        OPERATORS.each { name, info ->
            if( "Channel.${name}".toString() in defined )
                return
            add(name: "Channel.${name}", kind: 'operator', href: "reference/operator#${name.toLowerCase()}", summary: info[0], type: info[1])
        }

        KEYWORDS.each { name, info ->
            add(name: name, kind: 'keyword', href: info[0], summary: info[1])
        }
    }

    private void namespace(String name, Class clazz, String href, String text) {
        add(name: name, kind: 'namespace', href: href, summary: summary(text))
        for( final d : documented(clazz) ) {
            final type = d.method.returnType
            if( Namespace.isAssignableFrom(type) )
                namespace("${name}.${d.name}", type, href, d.text)
            else if( name == 'channel' )
                add(member(d, "${name}.", "${href}#${d.name.toLowerCase()}", 'channel factory'))
            else
                add(member(d, "${name}.", href))
        }
    }

    // processes

    void process() {
        final directives = documented(ProcessDsl.DirectiveDsl)
        for( final d : directives )
            add(name: d.name, kind: 'process directive', context: 'process', href: directiveHref(d.name), summary: summary(d.text))

        for( final d : documented(ProcessDsl) )
            add(member(d, '', d.name == 'task' ? 'reference/process/task-properties' : 'process#template', null, 'process'))

        final names = directives*.name as Set
        add(name: 'TaskConfig', kind: 'type', href: 'reference/process/task-properties', summary: summary(description(TaskConfig)))
        for( final d : documented(TaskConfig) ) {
            if( d.name in names )
                add(member(d, 'TaskConfig.', directiveHref(d.name), 'task directive value'))
            else
                add(member(d, 'TaskConfig.', "reference/process/task-properties#task${d.name.toLowerCase()}", 'task property'))
        }

        final typed = 'reference/process/inputs-outputs-typed'
        for( final d : documented(ProcessDsl.StageDsl) )
            add(member(d, '', "${typed}#stage-directives", 'stage directive', 'process.typed.stage'))
        for( final d : documented(ProcessDsl.OutputDslV2) + documented(FileOutputDsl) )
            add(member(d, '', "${typed}#outputs", 'output function', 'process.typed.output'))

        final legacy = 'reference/process/inputs-outputs'
        for( final d : documented(ProcessDsl.InputDslV1) )
            add(member(d, '', "${legacy}#inputs", 'input qualifier', 'process.input'))
        for( final d : documented(ProcessDsl.OutputDslV1) )
            add(member(d, '', "${legacy}#outputs", 'output qualifier', 'process.output'))
        OUTPUT_OPTIONS.each { name, info ->
            add(name: name, kind: 'output option', context: 'process.output', href: "${legacy}#generic-options", signature: info[0], summary: info[1])
        }

        SECTIONS.each { context, sections ->
            final kind = context.startsWith('workflow') ? 'workflow section' : 'process section'
            sections.each { name, info ->
                add(name: name, kind: kind, context: context, href: info[0], summary: info[1])
            }
        }
    }

    private static String directiveHref(String name) {
        return "reference/process/directives/${kebab(name)}"
    }

    // config

    void config() {
        final root = new TreeMap<String,SpecNode>(SpecNode.ROOT.children())
        for( final scope : Plugins.getExtensions(ConfigScope) ) {
            final clazz = scope.getClass()
            final name = clazz.getAnnotation(ScopeName)?.value()
            if( name == '' )
                root.putAll(SpecNode.Scope.of(clazz, '').children())
            else if( name )
                root.put(name, SpecNode.Scope.of(clazz, description(clazz) ?: ''))
        }
        root.each { name, node -> configNode(name, node, null) }
    }

    private void configNode(String name, SpecNode node, String page) {
        // the feature flags link to their own page, and the process scope holds the directives
        if( node instanceof SpecNode.Placeholder || name in ['nextflow.enable', 'nextflow.preview'] )
            return
        final text = node.description()
        if( node instanceof SpecNode.Option ) {
            final href = readMore(text) ?: "${page ?: 'reference/config/unscoped'}#${slug(name)}"
            final types = ((SpecNode.Option) node).types()
            add(name: name, kind: 'config setting', context: 'config', href: href, summary: summary(text), type: types ? typeName(types[0]) : null)
            return
        }
        final href = readMore(text) ?: page ?: "reference/config/${kebab(name)}"
        add(name: name, kind: 'config scope', context: 'config', href: href, summary: summary(text))
        // a DSL option, such as `plugins`, is a block of statements rather than settings
        if( node !instanceof SpecNode.Scope || name in ['process', 'agent'] )
            return
        ((SpecNode.Scope) node).children().sort().each { child, childNode ->
            configNode("${name}.${child}", childNode, page ?: href)
        }
    }

    // helpers

    /**
     * The documented methods of a class by name (the constant name of a getter),
     * each with its first described overload.
     */
    private static List<Map> documented(Class clazz) {
        final groups = new TreeMap<String,List<Method>>()
        for( final method : clazz.declaredMethods ) {
            if( method.isSynthetic() || Modifier.isStatic(method.modifiers) )
                continue
            final name = method.getAnnotation(Constant)?.value() ?: method.name
            groups.computeIfAbsent(name) { [] }.add(method)
        }
        return groups.collect { name, methods ->
            final method = methods
                .sort { a, b -> a.parameterCount <=> b.parameterCount ?: a.toString() <=> b.toString() }
                .find { it.getAnnotation(Description) }
            method ? [name: name, method: method, constant: method.getAnnotation(Constant) != null, text: description(method)] : null
        }.findAll()
    }

    private static Map member(Map d, String prefix, String href, String kind = null, String context = null) {
        final method = (Method) d.method
        final type = method.returnType == void ? null : typeName(method.genericReturnType)
        final signature = d.constant
            ? (type ? "${prefix}${d.name}: ${type}" : "${prefix}${d.name}")
            : "${prefix}${d.name}(${parameters(method)})" + (type ? " -> ${type}" : '')
        return [
            name: prefix + d.name,
            kind: kind ?: (d.constant ? 'property' : 'function'),
            context: context,
            href: href,
            signature: signature,
            summary: summary(d.text),
            type: type,
        ]
    }

    private static String parameters(Method method) {
        return method.parameters.collect { param ->
            final name = param.isVarArgs() ? "${param.name}..." : param.name
            final type = typeName(param.isVarArgs() ? param.type.componentType : param.parameterizedType)
            type ? "${name}: ${type}" : name
        }.join(', ')
    }

    /** The name of a type, or null for an untyped value. */
    private static String typeName(Type type) {
        final name = Types.getName(type)
        return name == '?' ? null : name
    }

    private static String description(AnnotatedElement element) {
        return element.getAnnotation(Description)?.value()?.stripIndent(true)?.trim()
    }

    private static final String READ_MORE = /\[Read more\]\((https:\/\/docs\.seqera\.io\/nextflow\/[^)]+)\)/

    /** The docs link at the end of a config description, relative to the docs site. */
    private static String readMore(String text) {
        final match = text =~ READ_MORE
        return match.find() ? match.group(1).substring(DOCS.length()) : null
    }

    /** The first sentence of a description, without links. */
    static String summary(String text) {
        if( !text )
            return null
        final paragraph = text
            .replaceAll(/\[Read more\]\([^)]*\)/, '')
            .split(/\n\s*\n/)[0]
            .replaceAll(/\[([^\]]*)\]\([^)]*\)/, '$1')
            .replaceAll(/\s+/, ' ')
            .trim()
        final match = paragraph =~ /^(.+?(?<!\be\.g|\bi\.e|\betc)[.!?])\s+(?=[A-Z`*(])/
        return match.find() ? match.group(1) : paragraph ?: null
    }

    /** The heading id that the docs site gives a heading. */
    static String slug(String text) {
        return text.toLowerCase().replaceAll(/[^\w\- ]/, '').replace(' ', '-')
    }

    static String kebab(String name) {
        return name.replaceAll(/([a-z0-9])([A-Z])/, '$1-$2').toLowerCase()
    }

    // definitions

    static final List<Class> TYPES = [
        Types.Bag_TYPE, Types.Boolean_TYPE, Types.Duration_TYPE, Types.Float_TYPE, Types.Integer_TYPE,
        Types.Iterable_TYPE, Types.List_TYPE, Types.Map_TYPE, Types.MemoryUnit_TYPE, Types.Path_TYPE,
        Types.Record_TYPE, Types.Set_TYPE, Types.String_TYPE, Channel, Tuple, Value, VersionNumber,
    ]

    static final Set<String> TYPE_NAMES = TYPES.collect { Types.getName(it) } as Set

    static final String SYNTAX = 'reference/syntax'

    // keep in sync with the headings of reference/operator in the Nextflow docs
    static final Map<String,List<String>> OPERATORS = [
        branch: ['Sends each item to one of several output channels, based on conditions.', null],
        buffer: ['Collects items into subsets and emits each subset.', 'Channel'],
        collate: ['Collects items into groups of N items.', 'Channel'],
        collect: ['Collects all items into a list and emits it as one item.', 'Value'],
        collectFile: ['Saves the items to one or more files, and emits the files.', 'Channel'],
        combine: ['Emits every combination of the items of two channels, or of a channel and a list.', 'Channel'],
        concat: ['Emits the items of two or more channels, one channel after another.', 'Channel'],
        count: ['Counts the items and emits the number.', 'Value'],
        countFasta: ['Counts the records in a channel of FASTA files.', 'Value'],
        countFastq: ['Counts the records in a channel of FASTQ files.', 'Value'],
        countJson: ['Counts the records in a channel of JSON files.', 'Value'],
        countLines: ['Counts the lines in a channel of text files.', 'Value'],
        cross: ['Emits every pair of items from two channels that have a matching key.', 'Channel'],
        distinct: ['Removes items that repeat the item before them.', 'Channel'],
        dump: ['Prints each item when the pipeline runs with the `-dump-channels` option.', 'Channel'],
        filter: ['Emits the items that satisfy a condition.', 'Channel'],
        first: ['Emits the first item, or the first item that matches a condition.', 'Value'],
        flatMap: ['Applies a mapping function to each item, and emits each element of a returned list.', 'Channel'],
        flatten: ['Emits each element of each list or collection separately.', 'Channel'],
        groupTuple: ['Collects tuples into groups by a key, and emits one tuple for each key.', 'Channel'],
        ifEmpty: ['Emits the items, or a default value if the channel is empty.', 'Channel'],
        join: ['Joins the items of two channels that have a matching key.', 'Channel'],
        last: ['Emits the last item.', 'Value'],
        map: ['Applies a mapping function to each item.', 'Channel'],
        max: ['Emits the item with the greatest value.', 'Value'],
        merge: ['Joins the items of two or more channels by their order.', 'Channel'],
        min: ['Emits the item with the lowest value.', 'Value'],
        mix: ['Emits the items of two or more channels in one channel.', 'Channel'],
        multiMap: ['Applies several mapping functions, with one output channel for each.', null],
        randomSample: ['Emits a random subset of the items.', 'Channel'],
        reduce: ['Applies an accumulator function to each item, and emits the final value.', 'Value'],
        set: ['Assigns the channel to a variable.', null],
        splitCsv: ['Splits CSV files or text into records.', 'Channel'],
        splitFasta: ['Splits FASTA files or text into sequences.', 'Channel'],
        splitFastq: ['Splits FASTQ files or text into sequences.', 'Channel'],
        splitJson: ['Splits JSON files or text into records.', 'Channel'],
        splitText: ['Splits text into chunks of N lines.', 'Channel'],
        subscribe: ['Calls a function for each item.', null],
        sum: ['Emits the sum of all items.', 'Value'],
        take: ['Emits the first N items.', 'Channel'],
        tap: ['Assigns the channel to a variable, and emits the items.', 'Channel'],
        toList: ['Collects all items into a list and emits it as one item.', 'Value'],
        toSortedList: ['Collects all items into a sorted list and emits it as one item.', 'Value'],
        transpose: ['Flattens the lists in each tuple, and emits each nested item separately.', 'Channel'],
        unique: ['Emits the unique items.', 'Channel'],
        until: ['Emits items until a condition is true.', 'Channel'],
        view: ['Prints each item to standard output.', 'Channel'],
    ]

    static final Map<String,List<String>> KEYWORDS = [
        enum: ["${SYNTAX}#enum-type", 'Defines an enum type, a fixed list of names.'],
        include: ["${SYNTAX}#include", 'Includes processes, workflows, functions and types from another script.'],
        output: ["${SYNTAX}#output-block", 'Declares the workflow outputs and how to publish them.'],
        params: ["${SYNTAX}#params-block", 'Declares the pipeline parameters, each with a type and an optional default value.'],
        process: ["${SYNTAX}#process", 'Defines a process, which runs a task for each set of inputs.'],
        record: ["${SYNTAX}#record-type", 'Defines a record type, a set of fields with a name and a type.'],
        workflow: ["${SYNTAX}#workflow", 'Defines a named workflow, or the entry workflow of the script.'],
    ].collectEntries { k, v -> [k, v*.toString()] }

    static final Map<String,Map<String,List<String>>> SECTIONS = [
        'process': [
            exec: ['process#native-execution', 'Runs Nextflow code for each task, without a job.'],
            input: ['reference/process/inputs-outputs#inputs', 'Declares the inputs of the process.'],
            output: ['reference/process/inputs-outputs#outputs', 'Declares the outputs of the process.'],
            script: ['process#script', 'Defines the script that each task runs.'],
            shell: ['process#shell', 'Defines the script that each task runs, with `!{…}` for Nextflow variables. Deprecated.'],
            stub: ['process#stub', 'Defines the script that each task runs with the `-stub` option.'],
            when: ['process#when', 'Defines a condition that each task must meet to run. Deprecated.'],
        ],
        'process.typed': [
            input: ['process-typed#inputs', 'Declares the inputs of the process, each with a name and a type.'],
            output: ['process-typed#outputs', 'Declares the outputs of the process.'],
            stage: ['process-typed#stage-directives', 'Stages inputs into the task directory.'],
            topic: ['process-typed#topics', 'Sends values to topic channels.'],
        ],
        'workflow': [
            emit: ['workflow#takes-and-emits', 'Declares the outputs of a named workflow.'],
            main: ["${SYNTAX}#workflow", 'Holds the statements of the workflow.'],
            onComplete: ["${SYNTAX}#workflow", 'Holds the statements that run when the workflow completes.'],
            onError: ["${SYNTAX}#workflow", 'Holds the statements that run when the workflow fails.'],
            publish: ["${SYNTAX}#workflow", 'Assigns channels to the workflow outputs.'],
            take: ['workflow#takes-and-emits', 'Declares the inputs of a named workflow.'],
        ],
    ].collectEntries { k, v -> [k, v.collectEntries { n, i -> [n, i*.toString()] }] }

    static final Map<String,List<String>> OUTPUT_OPTIONS = [
        emit: ['emit: <name>', 'Defines the name of the output channel.'],
        optional: ['optional: true | false', 'When `true`, the task does not fail if the output is missing.'],
        topic: ['topic: <name>', 'Sends the output to a topic channel with the given name.'],
    ]
}
