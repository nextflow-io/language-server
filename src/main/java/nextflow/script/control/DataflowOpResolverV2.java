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
package nextflow.script.control;

import java.util.List;

import nextflow.script.types.Bag;
import nextflow.script.types.Channel;
import nextflow.script.types.Record;
import nextflow.script.types.Tuple;
import nextflow.script.types.TypesEx;
import nextflow.script.types.Value;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.Expression;

import static nextflow.script.ast.ASTUtils.*;
import static nextflow.script.types.TypeCheckingUtils.*;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class DataflowOpResolverV2 {

    private static final ClassNode BAG_TYPE = ClassHelper.makeCached(Bag.class);
    private static final ClassNode CHANNEL_TYPE = ClassHelper.makeCached(Channel.class);
    private static final ClassNode RECORD_TYPE = ClassHelper.makeCached(Record.class);
    private static final ClassNode TUPLE_TYPE = ClassHelper.makeCached(Tuple.class);
    private static final ClassNode VALUE_TYPE = ClassHelper.makeCached(Value.class);

    /**
     * Resolve the return type of dataflow operators where applicable,
     * such as `cross`, `groupBy`, and `join`.
     *
     * @param lhsType
     * @param method
     * @param arguments
     */
    public ClassNode apply(ClassNode lhsType, MethodNode method, List<Expression> arguments) {
        var name = method.getName();

        if( "cross".equals(name) )
            return applyCross(lhsType, arguments);

        if( "groupBy".equals(name) )
            return applyGroupBy(lhsType, arguments);

        if( "join".equals(name) )
            return applyJoin(lhsType, arguments);

        return ClassHelper.dynamicType();
    }

    /**
     * Resolve the result type of a `cross` operation in terms of the left
     * and right operands.
     *
     * Given arguments of type `L` and `R`, `cross` produces a tuple of type
     * `(L, R)`.
     *
     * @param lhsType
     * @param arguments
     */
    private ClassNode applyCross(ClassNode lhsType, List<Expression> arguments) {
        var rhsType = dataflowElementType(getType(arguments.get(0)));
        var gts = new GenericsType[] {
            new GenericsType(lhsType),
            new GenericsType(rhsType)
        };
        return channelTupleType(gts);
    }

    /**
     * Resolve the result type of a `groupBy` operation.
     *
     * Given source tuples of type `(K, N, V)` or `(K, V)`,
     * `groupBy` produces a tuple of type `(K, Bag<V>)`.
     *
     * @param lhsType
     * @param arguments
     */
    private ClassNode applyGroupBy(ClassNode lhsType, List<Expression> arguments) {
        if( !TUPLE_TYPE.equals(lhsType) )
            return ClassHelper.dynamicType();
        var lgts = lhsType.getGenericsTypes();
        if( lgts == null || !(lgts.length == 2 || lgts.length == 3) )
            return ClassHelper.dynamicType();
        var keyType = lgts[0].getType();
        var valueType = lgts[lgts.length - 1].getType();
        var gts = new GenericsType[] {
            new GenericsType(keyType),
            new GenericsType(makeType(BAG_TYPE, valueType))
        };
        return channelTupleType(gts);
    }

    /**
     * Resolve the result type of a `join` operation in terms of the left
     * and right operands.
     *
     * Given two metching records R1 and R2, `join` produces R1 + R2.
     *
     * @param lhsType
     * @param arguments
     */
    private ClassNode applyJoin(ClassNode lhsType, List<Expression> arguments) {
        if( !RECORD_TYPE.equals(lhsType) )
            return ClassHelper.dynamicType();
        var argType = getType(arguments.get(arguments.size() - 1));
        var rhsType = dataflowElementType(argType);
        if( !RECORD_TYPE.equals(rhsType) )
            return ClassHelper.dynamicType();
        var elementType = recordSumType(lhsType, rhsType);
        return makeType(TypesEx.CHANNEL_TYPE_V2, elementType);
    }

    private static ClassNode dataflowElementType(ClassNode type) {
        if( CHANNEL_TYPE.equals(type) || VALUE_TYPE.equals(type) )
            return elementType(type);
        return ClassHelper.dynamicType();
    }

    private static ClassNode channelTupleType(GenericsType[] gts) {
        var tupleType = TUPLE_TYPE.getPlainNodeReference();
        tupleType.setGenericsTypes(gts);
        return makeType(TypesEx.CHANNEL_TYPE_V2, tupleType);
    }

}
