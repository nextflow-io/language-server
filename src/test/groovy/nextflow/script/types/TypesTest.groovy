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

package nextflow.script.types

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.GenericsType
import spock.lang.Specification

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class TypesTest extends Specification {

    def 'should render a type' () {
        when:
        def cn = ClassHelper.MAP_TYPE
        then:
        Types.getName(cn) == 'Map<K, V>'

        when:
        cn = new ClassNode(Map.class)
        then:
        Types.getName(cn) == 'Map'

        when:
        cn.setGenericsTypes(new GenericsType[] {
            new GenericsType(ClassHelper.STRING_TYPE),
            new GenericsType(ClassHelper.STRING_TYPE)
        })
        then:
        Types.getName(cn) == 'Map<String, String>'
    }

    def 'should render the return type of a method' () {
        when:
        def cn = ClassHelper.makeCached(TaskConfig)
        def returnType = cn.getDeclaredMethods('getExt')[0].getReturnType()
        then:
        Types.getName(returnType) == 'Map<String, String>'
    }

    def 'should render a functional type' () {
        given:
        def cn = TypesEx.CHANNEL_TYPE_V2

        when:
        def param = cn.getDeclaredMethods('filter')[0].getParameters()[0]
        then:
        TypesEx.getName(param.getType()) == '(E) -> Boolean'

        when:
        param = cn.getDeclaredMethods('map')[0].getParameters()[0]
        then:
        TypesEx.getName(param.getType()) == '(E) -> R'

        when:
        param = cn.getDeclaredMethods('reduce')[0].getParameters().last()
        then:
        TypesEx.getName(param.getType()) == '(R, E) -> R'
    }

    def 'should determine whether a type is assignable to another type' () {
        expect:
        TypesEx.isAssignableFrom(ClassHelper.makeCached(TARGET), ClassHelper.makeCached(SOURCE)) == RESULT

        where:
        TARGET      | SOURCE    | RESULT

        Integer     | Integer   | true
        Integer     | Float     | false
        Float       | Integer   | true
        Float       | Float     | true

        String      | String    | true
        String      | Integer   | false
        Integer     | String    | false

        Iterable    | Bag       | true
        Iterable    | List      | true
        Iterable    | Set       | true
    }

    def 'should provide specialized class nodes for v1/v2 dataflow' () {
        expect:
        TypesEx.CHANNEL_TYPE_V1 == ClassHelper.makeCached(Channel.class)
        TypesEx.CHANNEL_TYPE_V2 == ClassHelper.makeCached(Channel.class)

        when:
        def opsV1 = TypesEx.SCRIPT_IMPORTS_V1
            .find { cn -> cn.getNameWithoutPackage() == 'Channel' }
            .getMethods()
            .collect{ mn -> mn.getName() }
        then:
        opsV1.contains 'combine'
        opsV1.contains 'filter'
        opsV1.contains 'groupTuple'
        opsV1.contains 'join'
        opsV1.contains 'map'

        when:
        def opsV2 = TypesEx.SCRIPT_IMPORTS_V2
            .find { cn -> cn.getNameWithoutPackage() == 'Channel' }
            .getMethods()
            .collect{ mn -> mn.getName() }
        then:
        opsV2.contains 'cross'
        opsV2.contains 'filter'
        opsV2.contains 'groupBy'
        opsV2.contains 'join'
        opsV2.contains 'map'
    }

}
