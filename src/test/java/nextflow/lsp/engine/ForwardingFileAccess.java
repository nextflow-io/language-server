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
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * File access that forwards to the system file access, for tests to
 * override the methods they inject faults into.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ForwardingFileAccess implements FileAccess {

    @Override
    public String readText(Path path) throws IOException {
        return FileAccess.system().readText(path);
    }

    @Override
    public boolean exists(Path path) {
        return FileAccess.system().exists(path);
    }

    @Override
    public List<Path> walk(Path root, Predicate<Path> excluded, Predicate<Path> matcher) throws IOException {
        return FileAccess.system().walk(root, excluded, matcher);
    }

}
