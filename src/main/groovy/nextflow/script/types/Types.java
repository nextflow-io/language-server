/*
 * Copyright 2013-2024, Seqera Labs
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
package nextflow.script.types;

import java.nio.file.Path;
import java.util.List;

import org.codehaus.groovy.ast.ClassNode;

public class Types {

    public static final List<ClassNode> TYPES = List.of(
        new ClassNode(Channel.class),
        new ClassNode(Duration.class),
        new ClassNode(Manifest.class),
        new ClassNode(MemoryUnit.class),
        new ClassNode(NextflowMetadata.class),
        new ClassNode(Path.class),
        new ClassNode(TaskConfig.class),
        new ClassNode(VersionNumber.class),
        new ClassNode(WorkflowMetadata.class)
    );

    public static String normalize(String name) {
        if( "DataflowReadChannel".equals(name) )
            return "Channel";
        if( "DataflowWriteChannel".equals(name) )
            return "Channel";
        if( "DataflowVariable".equals(name) )
            return "Channel";
        return name;
    }

}
