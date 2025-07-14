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
package nextflow.lsp.services.script;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import groovy.yaml.YamlSlurper;
import nextflow.script.ast.ASTNodeMarker;
import nextflow.script.ast.AssignmentExpression;
import nextflow.script.ast.ProcessNodeV1;
import nextflow.script.ast.ProcessNodeV2;
import nextflow.script.ast.TupleParameter;
import nextflow.script.types.Record;
import nextflow.script.types.Tuple;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ExpressionTransformer;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;

import static nextflow.script.ast.ASTUtils.*;
import static org.codehaus.groovy.ast.tools.GeneralUtils.*;

/**
 * Convert a legacy process definition to a typed process.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ProcessConverter {

    private static final ClassNode PATH_TYPE = ClassHelper.makeCached(Path.class);

    private Map<String,ClassNode> specInputs;

    public ProcessConverter(URI uri) {
        specInputs = new ModuleSpecVisitor().getInputs(uri);
    }

    public ProcessNodeV2 apply(ProcessNodeV1 node) {
        var stagers = new ArrayList<Statement>();
        var inputs = typedInputs(node.inputs, stagers);

        var topics = new ArrayList<Statement>();
        var outputs = typedOutputs(node.outputs, topics);

        return new ProcessNodeV2(
            node.getName(),
            node.directives,
            inputs,
            blockOrEmpty(stagers),
            outputs,
            blockOrEmpty(topics),
            node.when,
            node.type,
            node.exec,
            node.stub);
    }

    private int nextInputId;

    private Parameter[] typedInputs(Statement inputs, List<Statement> stagers) {
        if( inputs.isEmpty() )
            return Parameter.EMPTY_ARRAY;
        nextInputId = 1;
        return asDirectives(inputs)
            .map(call -> typedInput(call, stagers))
            .toArray(Parameter[]::new);
    }

    /**
     * Convert a legacy input to a typed input.
     *
     * Stage directives are added as needed to handle
     * inputs that require custom staging, such as env
     * and stdin.
     *
     * @param call
     * @param stagers
     */
    private Parameter typedInput(MethodCallExpression call, List<Statement> stagers) {
        var qualifier = call.getMethodAsString();

        if( "each".equals(qualifier) ) {
            var arg = lastArg(call);
            if( arg instanceof VariableExpression ve ) {
                var name = ve.getName();
                var type = specInputs.getOrDefault(name, ClassHelper.dynamicType());
                return param(name, type);
            }
            if( arg instanceof MethodCallExpression mce ) {
                return typedInput(mce, stagers);
            }
            return nextParam(ClassHelper.dynamicType());
        }

        if( "env".equals(qualifier) ) {
            var param = nextParam(ClassHelper.STRING_TYPE);
            var envName = lastArg(call);
            var envValue = constX(param.getName());
            var stager = stmt(callThisX("env", args(envName, envValue)));
            stagers.add(stager);
            return param;
        }

        if( "file".equals(qualifier) || "path".equals(qualifier) ) {
            var type = isFileCollection(call) ? fileCollectionType() : PATH_TYPE;
            var param = param(call, type);
            if( param == null )
                param = nextParam(type);
            var stageName = stageName(call);
            if( stageName != null ) {
                var stageValue = constX(param.getName());
                var stager = stmt(callThisX("stageAs", args(stageName, stageValue)));
                stagers.add(stager);
            }
            return param;
        }

        if( "stdin".equals(qualifier) ) {
            var param = nextParam(ClassHelper.STRING_TYPE);
            var stager = stmt(callThisX("stdin", args(varX(param.getName()))));
            stagers.add(stager);
            return param;
        }

        if( "tuple".equals(qualifier) ) {
            var components = asMethodCallArguments(call).stream()
                .map((arg) -> {
                    if( arg instanceof MethodCallExpression mce )
                        return mce;
                    if( arg instanceof VariableExpression ve )
                        return callThisX(ve.getName(), new ArgumentListExpression());
                    return null;
                })
                .filter(mce -> mce != null)
                .map(mce -> typedInput(mce, stagers))
                .toArray(Parameter[]::new);
            var type = new ClassNode(Record.class);
            return new TupleParameter(type, components);
        }

        if( "val".equals(qualifier) ) {
            var name = lastArg(call).getText();
            var type = specInputs.getOrDefault(name, ClassHelper.dynamicType());
            return param(name, type);
        }

        throw new IllegalStateException();
    }

    private static Parameter param(String name, ClassNode type) {
        return new Parameter(type, name);
    }

    private static Parameter param(MethodCallExpression call, ClassNode type) {
        if( lastArg(call) instanceof VariableExpression ve )
            return param(ve.getName(), type);
        else
            return null;
    }

    private static Expression lastArg(MethodCallExpression call) {
        var args = asMethodCallArguments(call);
        return args.get(args.size() - 1);
    }

    private Parameter nextParam(ClassNode type) {
        var name = String.format("$in%d", nextInputId++);
        return param(name, type);
    }

    private static Expression stageName(MethodCallExpression call) {
        var namedArgs = namedArgs(call);
        if( namedArgs.containsKey("stageAs") )
            return namedArgs.get("stageAs");
        if( namedArgs.containsKey("name") )
            return namedArgs.get("name");
        var lastArg = lastArg(call);
        return lastArg instanceof VariableExpression ? null : lastArg;
    }

    private static boolean isFileCollection(MethodCallExpression call) {
        var args = asMethodCallArguments(call);
        if( args.size() > 0 && args.get(0) instanceof NamedArgumentListExpression nale ) {
            var arity = FileParamASTUtils.arityValue(nale);
            if( arity != null )
                return !"1".equals(arity);
        }
        return stageName(call) instanceof ConstantExpression ce
            && FileParamASTUtils.isGlobPattern(ce);
    }

    private static ClassNode fileCollectionType() {
        var result = new ClassNode(Set.class);
        result.setGenericsTypes(new GenericsType[] {
            new GenericsType(PATH_TYPE)
        });
        return result;
    }

    private int nextOutputId;

    private Statement typedOutputs(Statement outputs, List<Statement> topics) {
        if( outputs.isEmpty() )
            return EmptyStatement.INSTANCE;
        nextOutputId = 1;
        var statements = asDirectives(outputs)
            .map(call -> typedOutput(call, topics))
            .filter(call -> call != null)
            .toList();
        var fatRecord = fatRecord(statements);
        if( fatRecord == null )
            checkExpressionOutput(statements);
        return block(null, fatRecord != null ? List.of(stmt(fatRecord)) : statements);
    }

    private void checkExpressionOutput(List<Statement> statements) {
        if( statements.size() != 1 )
            return;
        var es = (ExpressionStatement) statements.get(0);
        if( es.getExpression() instanceof AssignmentExpression ae ) {
            var target = (VariableExpression) ae.getLeftExpression();
            if( !target.getName().startsWith("$out") )
                return;
            var source = ae.getRightExpression();
            es.setExpression(source);
        }
    }

    /**
     * Convert one or more "skinny tuple" outputs into a
     * single "fat record" output.
     *
     * For example, the following snippet:
     *
     *   output:
     *   bam = tuple(meta, file('*.bam'))
     *   bai = tuple(meta, file('*.bai'))
     *
     * Is converted to:
     *
     *   output:
     *   record(
     *     meta: meta,
     *     bam: file('*.bam'),
     *     bai: file('*.bai')
     *   )
     *
     * If the outputs do not conform to the expected structure,
     * the function returns null.
     *
     * @param statements
     */
    private Expression fatRecord(List<Statement> statements) {
        if( statements.isEmpty() )
            return null;
        var entries = new ArrayList<MapEntryExpression>();
        var names = new HashSet<String>();
        for( var output : statements ) {
            if( !isSkinnyTupleOutput(output) )
                return null;
            var es = (ExpressionStatement) output;
            var ae = (AssignmentExpression) es.getExpression();
            var target = (VariableExpression) ae.getLeftExpression();
            var source = (MethodCallExpression) ae.getRightExpression();
            var args = (ArgumentListExpression) source.getArguments();
            var first = (VariableExpression) args.getExpression(0);
            if( !names.contains(first.getName()) ) {
                entries.add(mapEntryX(first.getName(), first));
                names.add(first.getName());
            }
            entries.add(mapEntryX(target.getName(), args.getExpression(1)));
        }
        return callThisX("record", args(new NamedArgumentListExpression(entries)));
    }

    private boolean isSkinnyTupleOutput(Statement output) {
        return output instanceof ExpressionStatement es
            && es.getExpression() instanceof AssignmentExpression ae
            && ae.getRightExpression() instanceof MethodCallExpression mce
            && "tuple".equals(mce.getMethodAsString())
            && mce.getArguments() instanceof ArgumentListExpression args
            && args.getExpressions().size() == 2
            && args.getExpression(0) instanceof VariableExpression;
    }

    private static final Token RIGHT_SHIFT = Token.newSymbol(Types.RIGHT_SHIFT, -1, -1);

    /**
     * Convert a legacy output to a typed output.
     *
     * Topic emissions are added as needed for outputs
     * that specify a topic.
     *
     * @param call
     * @param topics
     */
    private Statement typedOutput(MethodCallExpression call, List<Statement> topics) {
        var namedArgs = namedArgs(call);
        var hasEmit = namedArgs.containsKey("emit");
        var hasTopic = namedArgs.containsKey("topic");
        var outputValue = typedOutputValue(call);
        if( hasTopic ) {
            var topicName = namedArgs.get("topic").getText();
            var topic = stmt(binX(outputValue, RIGHT_SHIFT, stringX(topicName)));
            topics.add(topic);
            if( !hasEmit )
                return null;
        }
        var outputName = hasEmit 
            ? namedArgs.get("emit").getText()
            : String.format("$out%d", nextOutputId++);
        return stmt(new AssignmentExpression(varX(outputName), outputValue));
    }

    /**
     * Get the named arguments of a method call as a
     * mapping of names to expressions.
     *
     * @param call
     */
    private static Map<String,Expression> namedArgs(MethodCallExpression call) {
        var entries = asNamedArgs(call).stream()
            .map((entry) -> {
                var name = entry.getKeyExpression().getText();
                var value = entry.getValueExpression();
                return Map.entry(name, value);
            })
            .toArray(Map.Entry[]::new);
        return Map.ofEntries(entries);
    }

    private static Expression typedOutputValue(MethodCallExpression call) {
        return new OutputDslTransformer().transform(call);
    }

    private static Expression stringX(String str) {
        var result = constX(str);
        result.putNodeMetaData(ASTNodeMarker.VERBATIM_TEXT, String.format("'%s'", str));
        return result;
    }

    private static Statement blockOrEmpty(List<Statement> statements) {
        return !statements.isEmpty()
            ? block(null, statements)
            : EmptyStatement.INSTANCE;
    }
}


class ModuleSpecVisitor {

    private static final ClassNode PATH_TYPE = ClassHelper.makeCached(Path.class);

    public Map<String,ClassNode> getInputs(URI uri) {
        try {
            return specInputs(uri);
        }
        catch( Exception e ) {
            System.err.println("Failed to parse module spec (meta.yml): " + e.toString());
            return Collections.emptyMap();
        }
    }

    private static Map<String,ClassNode> specInputs(URI uri) {
        var specPath = Path.of(uri).getParent().resolve("meta.yml");
        if( !Files.exists(specPath) )
            return Collections.emptyMap();
        var spec = (Map<String,?>) loadSpec(specPath);
        var inputs = (List) spec.get("input");
        var result = new HashMap<String,ClassNode>();
        inputs.stream()
            .flatMap(input -> inputEntries(input))
            .forEach((obj) -> {
                var entry = (Map.Entry) obj;
                var name = (String) entry.getKey();
                var inputSpec = (Map<String,?>) entry.getValue();
                var type = inputSpec.get("type") instanceof String s
                    ? inputType(s)
                    : null;
                if( type == null )
                    return;
                result.put(name, type);
            });
        return result;
    }

    private static Object loadSpec(Path specPath) {
        try {
            return new YamlSlurper().parse(specPath);
        }
        catch( IOException e ) {
            System.err.println("Failed to read module spec: " + e.toString());
            return null;
        }
    }

    private static Stream<Map.Entry> inputEntries(Object input) {
        if( input instanceof List inputList )
            return inputList.stream().flatMap(m -> inputEntries(m));
        if( input instanceof Map inputMap )
            return inputMap.entrySet().stream();
        return Stream.empty();
    }

    private static ClassNode inputType(String type) {
        if( "boolean".equals(type) )
            return ClassHelper.Boolean_TYPE;
        if( "file".equals(type) || "directory".equals(type) )
            return PATH_TYPE;
        if( "integer".equals(type) )
            return ClassHelper.Integer_TYPE;
        if( "map".equals(type) )
            return ClassHelper.MAP_TYPE.getPlainNodeReference();
        if( "number".equals(type) )
            return ClassHelper.Float_TYPE;
        if( "string".equals(type) )
            return ClassHelper.STRING_TYPE;
        return ClassHelper.dynamicType();
    }
}


class OutputDslTransformer implements ExpressionTransformer {

    private static final List<String> LEGACY_OPTS = List.of("arity", "emit", "topic");

    @Override
    public Expression transform(Expression node) {
        if( node == null )
            return null;

        if( node instanceof ArgumentListExpression ale )
            return transformArgumentListExpression(ale);

        if( node instanceof VariableExpression ve )
            return transformVariableExpression(ve);

        if( node instanceof MethodCallExpression mce )
            return transformMethodCallExpression(mce);

        return node.transformExpression(this);
    }

    private Expression transformArgumentListExpression(ArgumentListExpression ale) {
        var args = ale.getExpressions();
        if( args.size() > 0 && args.get(0) instanceof NamedArgumentListExpression nale ) {
            var entries = nale.getMapEntryExpressions().stream()
                .filter((e) -> (
                    !LEGACY_OPTS.contains(e.getKeyExpression().getText())
                ))
                .toList();
            var newArgs = new ArrayList<Expression>(args.size());
            if( !entries.isEmpty() )
                newArgs.add(new NamedArgumentListExpression(entries));
            for( var arg : args.subList(1, args.size()) )
                newArgs.add(transform(arg));
            return new ArgumentListExpression(newArgs);
        }
        return ale.transformExpression(this);
    }

    private Expression transformVariableExpression(VariableExpression ve) {
        if( "stdout".equals(ve) )
            return callThisX(ve.getName(), new ArgumentListExpression());
        return ve;
    }

    private Expression transformMethodCallExpression(MethodCallExpression mce) {
        if( !mce.isImplicitThis() )
            return mce;
        var name = mce.getMethodAsString();
        if( "val".equals(name) )
            return lastArg(mce);
        var arguments = mce.getArguments();
        if( "file".equals(name) || "path".equals(name) ) {
            name = isFileCollection(arguments) ? "files" : "file";
        }
        var result = callThisX(name, transform(arguments));
        result.setSourcePosition(mce);
        return result;
    }

    public static boolean isFileCollection(Expression arguments) {
        var args = ((TupleExpression) arguments).getExpressions();
        if( args.size() > 0 && args.get(0) instanceof NamedArgumentListExpression nale ) {
            var arity = FileParamASTUtils.arityValue(nale);
            if( arity != null )
                return !"1".equals(arity);
        }
        return false;
    }

    private static Expression lastArg(MethodCallExpression call) {
        var args = asMethodCallArguments(call);
        return args.get(args.size() - 1);
    }
}


class FileParamASTUtils {

    public static String arityValue(NamedArgumentListExpression nale) {
        return nale.getMapEntryExpressions().stream()
            .filter(entry -> (
                entry.getKeyExpression() instanceof ConstantExpression ce && "arity".equals(ce.getValue())
            ))
            .map(entry -> (
                entry.getValueExpression() instanceof ConstantExpression ce && ce.getValue() instanceof String s ? s : null
            ))
            .filter(value -> value != null)
            .findFirst().orElse(null);
    }

    public static boolean isGlobPattern(ConstantExpression ce) {
        return ce.getValue() instanceof String s && (s.contains("*") || s.contains("?"));
    }
}
