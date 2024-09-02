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
package nextflow.config.v2;

import java.util.ArrayList;
import java.util.List;

import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.SourceUnit;

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
public class ConfigNode extends ModuleNode {

    private List<ConfigStatement> configStatements = new ArrayList<>();

    public ConfigNode(SourceUnit sourceUnit) {
        super(sourceUnit);
    }

    public List<ConfigStatement> getConfigStatements() {
        return configStatements;
    }

    public void addConfigStatement(ConfigStatement statement) {
        configStatements.add(statement);
    }
}