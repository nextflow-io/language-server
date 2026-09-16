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

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import nextflow.config.ast.ConfigAssignNode;
import nextflow.config.ast.ConfigBlockNode;
import nextflow.config.ast.ConfigIncludeNode;
import nextflow.config.ast.ConfigStatement;
import nextflow.lsp.services.config.ConfigAstCache;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.ProcessNodeV1;
import nextflow.script.ast.ProcessNodeV2;
import nextflow.script.formatter.Formatter;
import nextflow.script.formatter.FormattingOptions;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.stmt.Statement;

import static nextflow.script.ast.ASTUtils.*;

/**
 * Resolve the config settings that apply to a process, as a cascade of
 * layers ordered by precedence, with the winning layer for each directive
 * marked as active.
 *
 * The cascade is static -- values are rendered as source text rather than
 * evaluated, since the language server has no config evaluator.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigPreviewProvider {

    private static final FormattingOptions FORMATTING_OPTIONS = new FormattingOptions(4, true);

    /**
     * Precedence layers, weakest first. See ProcessConfigBuilder in the
     * Nextflow runtime: label selectors are applied first, then name
     * selectors, then the process scope fills in whatever is still unset.
     */
    private static final int RANK_PROCESS_SCOPE = 0;
    private static final int RANK_PROCESS_BODY = 1;
    private static final int RANK_WITH_LABEL = 2;
    private static final int RANK_WITH_NAME = 3;

    /**
     * Nextflow appends these directives rather than overriding them, so every
     * layer applies and none of them wins. See REPEATABLE_DIRECTIVES in
     * ProcessConfigBuilder in the Nextflow runtime.
     */
    private static final Set<String> REPEATABLE_DIRECTIVES = Set.of("label", "module", "pod", "publishDir");

    private ScriptAstCache scriptAst;

    private ConfigAstCache configAst;

    public ConfigPreviewProvider(ScriptAstCache scriptAst, ConfigAstCache configAst) {
        this.scriptAst = scriptAst;
        this.configAst = configAst;
    }

    /**
     * Preview the config settings that apply to a process.
     *
     * @param documentUri
     * @param processName
     * @param profiles
     *      The config profiles to apply, in order of increasing precedence.
     * @param qualifiedName
     *      The name of the invocation to preview, including the scope of the
     *      enclosing workflows, or null to preview the process on its own.
     */
    public Map<String,Object> previewConfig(String documentUri, String processName, List<String> profiles, String qualifiedName) {
        var uri = URI.create(documentUri);
        if( !scriptAst.hasAST(uri) || scriptAst.hasErrors(uri) )
            return Map.of("error", "Config preview cannot be shown because the script has errors.");

        var processNode = scriptAst.getProcessNodes(uri).stream()
            .filter(pn -> pn.getName().equals(processName))
            .findFirst()
            .orElse(null);
        if( processNode == null )
            return Map.of("error", "Process '" + processName + "' was not found.");

        var rootConfig = configAst != null ? rootConfigUri() : null;
        if( rootConfig == null )
            return Map.of("error", "Config preview cannot be shown because the workspace has no nextflow.config file.");
        var activeProfiles = profiles != null ? profiles : List.<String>of();
        var labels = labels(processNode);
        var names = selectorNames(processName, qualifiedName);

        if( !configAst.hasAST(rootConfig) )
            return Map.of("error", "Config preview cannot be shown because nextflow.config has errors.");

        var collector = new ConfigCollector();
        collector.walk(rootConfig, null, List.of(), null);
        // an unresolved include leaves out settings that may well be the ones
        // that win, so the cascade would be wrong rather than incomplete
        if( collector.errored != null )
            return Map.of("error", "Config preview cannot be shown because " + relativePath(collector.errored, rootConfig) + " has errors.");

        var entries = new ArrayList<>(collector.entries);
        entries.addAll(bodyEntries(processNode));

        var applied = entries.stream()
            .filter(entry -> matchesProfile(entry, activeProfiles))
            .filter(entry -> matchesProcess(entry, names, labels))
            .sorted(strength(activeProfiles))
            .toList();

        var result = new LinkedHashMap<String,Object>();
        result.put("process", qualifiedName != null ? qualifiedName : processName);
        result.put("labels", labels);
        result.put("profiles", List.copyOf(collector.profileNames));
        result.put("activeProfiles", activeProfiles);
        result.put("directives", directives(applied, rootConfig));
        return Map.of("result", result);
    }

    /**
     * Find the root config file of the workspace, which is the topmost
     * `nextflow.config` in the config cache.
     */
    private URI rootConfigUri() {
        return configAst.getUris().stream()
            .filter(uri -> uri.getPath() != null && uri.getPath().endsWith("/nextflow.config"))
            .min(Comparator.comparingInt(uri -> uri.getPath().length()))
            .orElse(null);
    }

    private static List<String> labels(ProcessNode node) {
        var result = new ArrayList<String>();
        asDirectives(directivesOf(node)).forEach((call) -> {
            if( !"label".equals(call.getMethodAsString()) )
                return;
            var args = asMethodCallArguments(call);
            if( args.size() == 1 && args.get(0) instanceof ConstantExpression ce )
                result.add(ce.getText());
        });
        return result;
    }

    private static Statement directivesOf(ProcessNode node) {
        if( node instanceof ProcessNodeV1 v1 )
            return v1.directives;
        if( node instanceof ProcessNodeV2 v2 )
            return v2.directives;
        return null;
    }

    /**
     * Collect the directives declared in the process definition. They are
     * overridden by config selectors but take precedence over the plain
     * process scope.
     *
     * @param node
     */
    private List<Entry> bodyEntries(ProcessNode node) {
        var uri = scriptAst.getURI(node);
        var result = new ArrayList<Entry>();
        asDirectives(directivesOf(node)).forEach((call) -> {
            var name = call.getMethodAsString();
            if( name == null )
                return;
            result.add(new Entry(name, formatArguments(call), "process body", RANK_PROCESS_BODY, null, uri, call.getLineNumber(), result.size()));
        });
        return result;
    }

    private static boolean matchesProfile(Entry entry, List<String> activeProfiles) {
        return entry.profile == null || activeProfiles.contains(entry.profile);
    }

    /**
     * Collect the names that a `withName` selector can match: the process
     * name, the include alias, and the fully qualified name. See applyConfig
     * in ProcessConfigBuilder in the Nextflow runtime.
     *
     * @param processName
     * @param qualifiedName
     */
    private static List<String> selectorNames(String processName, String qualifiedName) {
        if( qualifiedName == null )
            return List.of(processName);
        var alias = qualifiedName.substring(qualifiedName.lastIndexOf(':') + 1);
        return Stream.of(processName, alias, qualifiedName).distinct().toList();
    }

    private static boolean matchesProcess(Entry entry, List<String> names, List<String> labels) {
        if( entry.rank == RANK_WITH_LABEL )
            return matchesLabels(labels, selectorPattern(entry.source));
        if( entry.rank == RANK_WITH_NAME )
            return names.stream().anyMatch(name -> matchesSelector(name, selectorPattern(entry.source)));
        return true;
    }

    private static String selectorPattern(String source) {
        return source.substring(source.indexOf(':') + 1).trim();
    }

    /**
     * Order entries from weakest to strongest: by precedence layer, then by
     * profile (profiles override the base config, and later profiles override
     * earlier ones), then by the order in which they were declared.
     *
     * @param activeProfiles
     */
    private static Comparator<Entry> strength(List<String> activeProfiles) {
        return Comparator
            .comparingInt((Entry entry) -> entry.rank)
            .thenComparingInt(entry -> activeProfiles.indexOf(entry.profile))
            .thenComparingInt(entry -> entry.seq);
    }

    /**
     * Group the applied entries by directive name, with the winning layer
     * first. Entries are given from weakest to strongest.
     *
     * @param applied
     * @param rootConfig
     */
    private List<Map<String,Object>> directives(List<Entry> applied, URI rootConfig) {
        var layers = new LinkedHashMap<String,List<Entry>>();
        for( var entry : applied )
            layers.computeIfAbsent(entry.name, (k) -> new ArrayList<>()).add(0, entry);

        return layers.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map((e) -> {
                var repeats = REPEATABLE_DIRECTIVES.contains(e.getKey());
                var values = new ArrayList<Map<String,Object>>();
                for( var entry : e.getValue() )
                    values.add(layer(entry, repeats || values.isEmpty(), rootConfig));
                return Map.of("name", (Object) e.getKey(), "layers", values);
            })
            .toList();
    }

    private static Map<String,Object> layer(Entry entry, boolean active, URI rootConfig) {
        var result = new LinkedHashMap<String,Object>();
        result.put("value", entry.value);
        result.put("source", entry.source);
        result.put("profile", entry.profile);
        result.put("file", relativePath(entry.uri, rootConfig));
        result.put("uri", entry.uri.toString());
        result.put("line", entry.line);
        result.put("active", active);
        return result;
    }

    private static String relativePath(URI uri, URI rootConfig) {
        try {
            return Path.of(rootConfig).getParent().relativize(Path.of(uri)).toString();
        }
        catch( Exception e ) {
            return uri.toString();
        }
    }

    private static String formatArguments(MethodCallExpression call) {
        var fmt = new Formatter(FORMATTING_OPTIONS);
        fmt.visitArguments(asMethodCallArguments(call), false);
        return fmt.toString().trim();
    }

    private static String formatValue(Expression value) {
        var fmt = new Formatter(FORMATTING_OPTIONS);
        fmt.visit(value);
        return fmt.toString().trim();
    }

    /**
     * Determine whether any of the given labels match the given
     * `withLabel` selector.
     *
     * Adapted from ProcessConfigBuilder in the Nextflow runtime.
     *
     * @param labels
     * @param pattern
     */
    private static boolean matchesLabels(List<String> labels, String pattern) {
        var isNegated = pattern.startsWith("!");
        var regex = compile(isNegated ? pattern.substring(1).trim() : pattern);
        if( regex == null )
            return false;
        for( var label : labels ) {
            if( regex.matcher(label).matches() )
                return !isNegated;
        }
        return isNegated;
    }

    /**
     * Determine whether the given process name matches the given
     * `withName` selector.
     *
     * Adapted from ProcessConfigBuilder in the Nextflow runtime.
     *
     * @param name
     * @param pattern
     */
    private static boolean matchesSelector(String name, String pattern) {
        var isNegated = pattern.startsWith("!");
        var regex = compile(isNegated ? pattern.substring(1).trim() : pattern);
        if( regex == null )
            return false;
        return regex.matcher(name).matches() ^ isNegated;
    }

    private static Pattern compile(String pattern) {
        try {
            return Pattern.compile(pattern);
        }
        catch( PatternSyntaxException e ) {
            return null;
        }
    }

    /**
     * A single config setting in the `process` scope, or a directive in a
     * process definition.
     *
     * @param name      directive name, e.g. `cpus` or `ext.args`
     * @param value     the source text of the value
     * @param source    the layer that declared it, e.g. `withName:FOO`
     * @param rank      the precedence layer
     * @param profile   the enclosing config profile, or null for the base config
     * @param uri       the file that declared it
     * @param line
     * @param seq       the order in which it was declared
     */
    private static record Entry(
        String name,
        String value,
        String source,
        int rank,
        String profile,
        URI uri,
        int line,
        int seq
    ) {}

    /**
     * Collect the settings in the `process` scope across a config file and
     * the config files that it includes.
     */
    private class ConfigCollector {

        private final List<Entry> entries = new ArrayList<>();

        private final Set<String> profileNames = new LinkedHashSet<>();

        private final Deque<URI> including = new ArrayDeque<>();

        private int seq = 0;

        /** The first config file found to have errors, if any. */
        private URI errored;

        void walk(URI uri, String profile, List<String> scope, String selector) {
            if( !configAst.hasAST(uri) || including.contains(uri) )
                return;
            if( errored == null && (configAst.hasSyntaxErrors(uri) || configAst.hasIncludeErrors(uri)) )
                errored = uri;
            including.push(uri);
            visit(configAst.getConfigNode(uri).getConfigStatements(), uri, profile, scope, selector);
            including.pop();
        }

        private void visit(List<ConfigStatement> statements, URI uri, String profile, List<String> scope, String selector) {
            for( var stmt : statements ) {
                if( stmt instanceof ConfigAssignNode node )
                    visitAssign(node, uri, profile, scope, selector);
                else if( stmt instanceof ConfigBlockNode node )
                    visitBlock(node, uri, profile, scope, selector);
                else if( stmt instanceof ConfigIncludeNode node )
                    visitInclude(node, uri, profile, scope, selector);
            }
        }

        private void visitAssign(ConfigAssignNode node, URI uri, String profile, List<String> scope, String selector) {
            var names = new ArrayList<String>(scope);
            names.addAll(node.names);
            // a fully qualified name may specify the profile, e.g. profiles.docker.process.cpus
            if( names.size() > 2 && "profiles".equals(names.get(0)) ) {
                profile = names.get(1);
                profileNames.add(profile);
                names = new ArrayList<>(names.subList(2, names.size()));
            }
            if( names.size() < 2 || !"process".equals(names.get(0)) )
                return;
            var name = String.join(".", names.subList(1, names.size()));
            addEntry(name, formatValue(node.value), node, uri, profile, selector);
        }

        private void visitBlock(ConfigBlockNode node, URI uri, String profile, List<String> scope, String selector) {
            // a selector block applies to the enclosing scope
            if( node.kind != null ) {
                visit(node.statements, uri, profile, scope, node.kind + ":" + node.name);
                return;
            }
            // each block in the profiles scope is a profile
            if( scope.isEmpty() && "profiles".equals(node.name) ) {
                for( var stmt : node.statements ) {
                    if( !(stmt instanceof ConfigBlockNode child) || child.kind != null )
                        continue;
                    profileNames.add(child.name);
                    visit(child.statements, uri, child.name, List.of(), selector);
                }
                return;
            }
            var nested = new ArrayList<>(scope);
            nested.add(node.name);
            visit(node.statements, uri, profile, nested, selector);
        }

        private void visitInclude(ConfigIncludeNode node, URI uri, String profile, List<String> scope, String selector) {
            if( !(node.source instanceof ConstantExpression ce) )
                return;
            var includeUri = ConfigAstCache.resolveIncludeUri(uri, ce.getText());
            if( includeUri != null )
                walk(includeUri, profile, scope, selector);
        }

        private void addEntry(String name, String value, ASTNode node, URI uri, String profile, String selector) {
            int rank;
            String source;
            if( selector == null ) {
                rank = RANK_PROCESS_SCOPE;
                source = "process config";
            }
            else if( selector.startsWith("withLabel:") ) {
                rank = RANK_WITH_LABEL;
                source = selector;
            }
            else if( selector.startsWith("withName:") ) {
                rank = RANK_WITH_NAME;
                source = selector;
            }
            else {
                return;
            }
            entries.add(new Entry(name, value, source, rank, profile, uri, node.getLineNumber(), seq++));
        }

    }

}
