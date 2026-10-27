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
package nextflow.lsp.engine;

import java.net.URI;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import nextflow.lsp.services.ErrorReportingMode;
import nextflow.lsp.services.LanguageServerConfiguration;
import nextflow.lsp.util.JsonUtils;

/**
 * Parse the settings of a `workspace/didChangeConfiguration`
 * notification. A missing or invalid value keeps the current
 * value, and an invalid value is reported, so that one bad key
 * doesn't drop the others.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
final class Settings {

    private Settings() {
    }

    /**
     * Apply settings to a configuration.
     *
     * @param current
     * @param settings
     * @param warn receives a message for each invalid value
     */
    static LanguageServerConfiguration apply(LanguageServerConfiguration current, Object settings, Consumer<String> warn) {
        return new LanguageServerConfiguration(
            get("nextflow.dag.direction", () -> JsonUtils.getString(settings, "nextflow.dag.direction"), current.dagDirection(), warn),
            get("nextflow.dag.verbose", () -> JsonUtils.getBoolean(settings, "nextflow.dag.verbose"), current.dagVerbose(), warn),
            get("nextflow.errorReportingMode", () -> errorReportingMode(settings), current.errorReportingMode(), warn),
            get("nextflow.files.exclude", () -> JsonUtils.getStringArray(settings, "nextflow.files.exclude"), current.excludePatterns(), warn),
            get("nextflow.completion.extended", () -> JsonUtils.getBoolean(settings, "nextflow.completion.extended"), current.extendedCompletion(), warn),
            get("nextflow.formatting.harshilAlignment", () -> JsonUtils.getBoolean(settings, "nextflow.formatting.harshilAlignment"), current.harshilAlignment(), warn),
            get("nextflow.formatting.maheshForm", () -> JsonUtils.getBoolean(settings, "nextflow.formatting.maheshForm"), current.maheshForm(), warn),
            get("nextflow.completion.maxItems", () -> JsonUtils.getInteger(settings, "nextflow.completion.maxItems"), current.maxCompletionItems(), warn),
            get("nextflow.pluginRegistryUrl", () -> registryUrl(settings), current.pluginRegistryUrl(), warn),
            get("nextflow.formatting.sortDeclarations", () -> JsonUtils.getBoolean(settings, "nextflow.formatting.sortDeclarations"), current.sortDeclarations(), warn)
        );
    }

    /**
     * Get the `nextflow.debug` setting, or null if it is not set.
     *
     * @param settings
     */
    static Boolean debug(Object settings) {
        try {
            return JsonUtils.getBoolean(settings, "nextflow.debug");
        }
        catch( RuntimeException e ) {
            return null;
        }
    }

    private static ErrorReportingMode errorReportingMode(Object settings) {
        var string = JsonUtils.getString(settings, "nextflow.errorReportingMode");
        if( string == null )
            return null;
        return ErrorReportingMode.valueOf(string.toUpperCase());
    }

    private static String registryUrl(Object settings) {
        var string = JsonUtils.getString(settings, "nextflow.pluginRegistryUrl");
        if( string == null )
            return null;
        var uri = URI.create(string);
        if( !"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()) )
            throw new IllegalArgumentException("not an HTTP URL: " + string);
        return string;
    }

    private static <T> T get(String key, Supplier<T> value, T current, Consumer<String> warn) {
        try {
            var result = value.get();
            if( result instanceof List<?> list )
                return (T) List.copyOf(list);
            return result != null ? result : current;
        }
        catch( RuntimeException e ) {
            warn.accept("Ignoring invalid value for setting '" + key + "': " + e.getMessage());
            return current;
        }
    }

}
