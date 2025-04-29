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
package nextflow.lsp.util;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class JsonUtils {

    public static Object asJson(Object value) {
        if( value == null )
            return JsonNull.INSTANCE;
        if( value instanceof Boolean b )
            return new JsonPrimitive(b);
        if( value instanceof Number n )
            return new JsonPrimitive(n);
        if( value instanceof String s )
            return new JsonPrimitive(s);
        return value;
    }

    public static List<String> getStringArray(Object json, String path) {
        var value = getObjectPath(json, path);
        if( value == null || !value.isJsonArray() )
            return null;
        var result = new ArrayList<String>();
        for( var el : value.getAsJsonArray() ) {
            try {
                result.add(el.getAsString());
            }
            catch( ClassCastException e ) {
                continue;
            }
        }
        return result;
    }

    public static Boolean getBoolean(Object json, String path) {
        var value = getObjectPath(json, path);
        if( value == null )
            return null;
        try {
            return value.getAsBoolean();
        }
        catch( ClassCastException e ) {
            return null;
        }
    }

    public static Integer getInteger(Object json, String path) {
        var value = getObjectPath(json, path);
        if( value == null )
            return null;
        try {
            return value.getAsInt();
        }
        catch( ClassCastException e ) {
            return null;
        }
    }

    public static String getString(Object json, String path) {
        var value = getObjectPath(json, path);
        if( value == null )
            return null;
        try {
            return value.getAsString();
        }
        catch( ClassCastException e ) {
            return null;
        }
    }

    public static String getString(Object json) {
        return json instanceof JsonPrimitive jp ? jp.getAsString() : null;
    }

    private static JsonElement getObjectPath(Object json, String path) {
        if( !(json instanceof JsonObject) )
            return null;

        JsonObject object = (JsonObject) json;
        var names = path.split("\\.");
        for( int i = 0; i < names.length - 1; i++ ) {
            var scope = names[i];
            if( !object.has(scope) || !object.get(scope).isJsonObject() )
                return null;
            object = object.get(scope).getAsJsonObject();
        }

        var property = names[names.length - 1];
        if( !object.has(property) )
            return null;
        return object.get(property);
    }

}
