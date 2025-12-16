# Nextflow IntelliJ IDEA Plugin

IntelliJ IDEA plugin providing complete Nextflow language support through LSP4IJ integration with the [Nextflow Language Server](https://github.com/nextflow-io/language-server).

## Features

- **Code Completion** - Intelligent suggestions for Nextflow syntax
- **Error Diagnostics** - Real-time error reporting and validation  
- **Go to Definition** - Navigate to variable and function definitions
- **Find References** - Locate all uses of variables and functions
- **Hover Documentation** - Context-sensitive help and documentation
- **Code Formatting** - Automatic code formatting
- **Semantic Highlighting** - Advanced syntax highlighting
- **DAG Preview** - Workflow visualization capabilities
- **File Support** - `.nf`, `.nf.test`, `nextflow.config`, and `*.config` files

## Requirements

- IntelliJ IDEA 2023.3 or newer
- Java 17 or later
- [LSP4IJ plugin](https://plugins.jetbrains.com/plugin/23257-lsp4ij)
- [Nextflow Language Server](https://github.com/nextflow-io/language-server/releases)

## Installation

### 1. Install Prerequisites
1. Install **LSP4IJ** plugin in IntelliJ IDEA (File → Settings → Plugins → Marketplace)
2. Download `language-server-all.jar` from [releases](https://github.com/nextflow-io/language-server/releases)
3. Place the JAR at: `~/.local/share/nextflow/language-server/language-server-all.jar`

### 2. Build and Install Plugin
```bash
cd intellij-plugin
./gradlew jar prepareSandbox
```

Install in IntelliJ IDEA:
- Go to **File → Settings → Plugins → Install Plugin from Disk**
- Select the plugin from `build/idea-sandbox/plugins/nextflow-intellij-plugin/`
- Restart IntelliJ IDEA

### 3. Testing
1. Create or open a `.nf` file
2. The plugin automatically connects to the Nextflow Language Server
3. All language features become available immediately
4. Monitor connection in **View → Tool Windows → LSP Consoles**

## Configuration

The plugin automatically discovers the language server JAR in these locations:
1. `~/.local/share/nextflow/language-server/language-server-all.jar`
2. `/usr/local/share/nextflow/language-server/language-server-all.jar` 
3. `/opt/nextflow/language-server/language-server-all.jar`
4. `language-server-all.jar` (current directory)

## Architecture

### Plugin Structure
```
src/main/
├── java/io/nextflow/intellij/
│   ├── NextflowFileType.java              # File type definition
│   ├── NextflowLanguageServerFactory.java # LSP4IJ factory
│   └── NextflowStreamConnectionProvider.java # Language server connection
└── resources/META-INF/
    └── plugin.xml                         # Plugin configuration
```

### LSP4IJ Integration
- Uses LSP4IJ extension points for server definition and file mapping
- Manages language server process lifecycle automatically
- Provides all standard LSP features through proper API usage
- Integrates seamlessly with IntelliJ's language infrastructure

## Troubleshooting

- **Language Server Not Found**: Ensure the JAR is in one of the expected locations
- **LSP Connection Issues**: Check **LSP Consoles** for detailed error messages
- **File Not Recognized**: Verify the Groovy plugin is enabled
- **Java Version Issues**: Ensure Java 17+ is available on the system PATH

## References

- [GitHub Issue #14](https://github.com/nextflow-io/language-server/issues/14)
- [LSP4IJ Documentation](https://github.com/redhat-developer/lsp4ij)
- [IntelliJ Plugin Development](https://plugins.jetbrains.com/docs/intellij/)