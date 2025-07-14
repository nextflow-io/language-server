/*
 * Copyright 2024-2025, Seqera Labs
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

package nextflow.lsp.ast

import groovy.lang.groovydoc.Groovydoc
import nextflow.script.ast.FeatureFlagNode
import nextflow.script.ast.FunctionNode
import nextflow.script.ast.ProcessNode
import nextflow.script.ast.WorkflowNode
import nextflow.script.dsl.FeatureFlagDsl
import nextflow.script.dsl.ProcessDsl
import nextflow.script.dsl.ScriptDsl
import nextflow.script.namespaces.ChannelNamespace
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.stmt.EmptyStatement
import spock.lang.Specification

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ASTNodeStringUtilsTest extends Specification {

    def 'should get the label and docs for a class' () {
        when:
        def classNode = Mock(ClassNode) {
            isEnum() >> false
            isResolved() >> true
            getNameWithoutPackage() >> 'Channel'
        }
        then:
        ASTNodeStringUtils.getLabel(classNode) == 'class Channel'

        when:
        def enumNode = Mock(ClassNode) {
            isEnum() >> true
            isResolved() >> true
            getNameWithoutPackage() >> 'SequenceTypes'
            getGroovydoc() >> Mock(Groovydoc) {
                isPresent() >> true
                getContent() >> '''\
                    /**
                     * Enumeration of sequence types.
                     */
                    '''.stripIndent(true)
            }
        }
        then:
        ASTNodeStringUtils.getLabel(enumNode) == 'enum SequenceTypes'
        ASTNodeStringUtils.getDocumentation(enumNode) == 'Enumeration of sequence types.'
    }

    def 'should get the label and docs for a feature flag' () {
        when:
        def ffn = new FeatureFlagNode('nextflow.enable.strict', null)
        ffn.target = new ClassNode(FeatureFlagDsl.class).getDeclaredField('strict')

        then:
        ASTNodeStringUtils.getLabel(ffn) == '(feature flag) nextflow.enable.strict'
        ASTNodeStringUtils.getDocumentation(ffn) == 'When `true`, the pipeline is executed in [strict mode](https://nextflow.io/docs/latest/reference/feature-flags.html).'
    }

    def 'should get the label and docs for a workflow' () {
        when:
        def entry = Mock(WorkflowNode) {
            isEntry() >> true
        }
        then:
        ASTNodeStringUtils.getLabel(entry) == 'workflow <entry>'

        when:
        def node = Mock(WorkflowNode) {
            isEntry() >> false
            getName() >> 'FOO'
            getParameters() >> Parameter.EMPTY_ARRAY
            emits >> EmptyStatement.INSTANCE
            getGroovydoc() >> Mock(Groovydoc) {
                isPresent() >> true
                getContent() >> '''\
                    /**
                     * Run the FOO workflow.
                     */
                    '''.stripIndent(true)
            }
        }
        then:
        ASTNodeStringUtils.getLabel(node) == '''
            workflow FOO {
              take:
              <none>

              emit:
              <none>
            }
            '''.stripIndent(true).trim()
        ASTNodeStringUtils.getDocumentation(node) == 'Run the FOO workflow.'
    }

    def 'should get the label and docs for a process' () {
        when:
        def node = Mock(ProcessNode) {
            getName() >> 'BAR'
            inputs >> EmptyStatement.INSTANCE
            outputs >> EmptyStatement.INSTANCE
            getGroovydoc() >> Mock(Groovydoc) {
                isPresent() >> true
                getContent() >> '''\
                    /**
                     * Run the BAR process.
                     */
                    '''.stripIndent(true)
            }
        }
        then:
        ASTNodeStringUtils.getLabel(node) == '''
            process BAR {
              input:
              <none>

              output:
              <none>
            }
            '''.stripIndent(true).trim()
        ASTNodeStringUtils.getDocumentation(node) == 'Run the BAR process.'
    }

    def 'should get the label and docs for a function' () {
        when:
        def node = Spy(new FunctionNode(
            'sayHello',
            ClassHelper.OBJECT_TYPE,
            [ new Parameter(ClassHelper.OBJECT_TYPE, 'message'), new Parameter(ClassHelper.OBJECT_TYPE, 'target') ] as Parameter[],
            null
        ))
        node.getGroovydoc() >> Mock(Groovydoc) {
            isPresent() >> true
            getContent() >> '''\
                /**
                 * Say hello to someone.
                 */
                '''.stripIndent(true)
        }
        then:
        ASTNodeStringUtils.getLabel(node) == 'def sayHello(message, target)'
        ASTNodeStringUtils.getDocumentation(node) == 'Say hello to someone.'
    }

    def 'should get the label and docs for a built-in function' () {
        when:
        def node = new ClassNode(ScriptDsl.class).getDeclaredMethods('env').first()
        then:
        ASTNodeStringUtils.getLabel(node) == 'env(name: String) -> String'
        ASTNodeStringUtils.getDocumentation(node) == '''
            Get the value of an environment variable from the launch environment.
            '''.stripIndent(true).trim()
    }

    def 'should get the label and docs for a namespace function' () {
        when:
        def node = new ClassNode(ChannelNamespace.class).getDeclaredMethods('of').first()
        then:
        ASTNodeStringUtils.getLabel(node) == 'of(values...: E) -> Channel<E>'
        ASTNodeStringUtils.getDocumentation(node) == '''
            Create a channel that emits each argument.

            [Read more](https://nextflow.io/docs/latest/reference/channel.html#of)
            '''.stripIndent(true).trim()
    }

    // def 'should get the label and docs for a method' () {
    //     when:
    //     def node = new ClassNode(Iterable.class).getDeclaredMethods('collect').first()
    //     then:
    //     ASTNodeStringUtils.getLabel(node) == 'Iterable collect(arg0: (E) -> R) -> Iterable<R>'
    //     ASTNodeStringUtils.getDocumentation(node) == '''
    //         Returns a new iterable with each element transformed by the given closure.
    //         '''.stripIndent(true).trim()
    // }

    def 'should get the label and docs for a process directive' () {
        when:
        def node = new ClassNode(ProcessDsl.DirectiveDsl.class).getDeclaredMethods('executor').first()
        then:
        ASTNodeStringUtils.getLabel(node) == '(process directive) executor'
        ASTNodeStringUtils.getDocumentation(node) == '''
            The `executor` defines the underlying system where tasks are executed.

            [Read more](https://nextflow.io/docs/latest/reference/process.html#executor)
            '''.stripIndent(true).trim()
    }

}
