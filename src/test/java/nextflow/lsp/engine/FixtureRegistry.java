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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin registry client that serves the fixtures under
 * `src/test/resources/registry`, records every fetch, and can be
 * told to fail.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public final class FixtureRegistry implements PluginRegistryClient {

    public final List<String> fetches = Collections.synchronizedList(new ArrayList<>());

    public final Map<String, Result> overrides = Collections.synchronizedMap(new HashMap<>());

    @Override
    public Result fetch(String name, String version) {
        var ref = version != null ? name + "@" + version : name;
        fetches.add(ref);
        var override = overrides.get(ref);
        if( override != null )
            return override;
        var resource = "/registry/v1/plugins/" + name + "/" + version + ".json";
        try( var stream = FixtureRegistry.class.getResourceAsStream(resource) ) {
            if( stream == null )
                return new NotFound();
            return HttpPluginRegistryClient.parseResponse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch( IOException e ) {
            throw new UncheckedIOException(e);
        }
    }

}
