#!/bin/bash
#
# lsp-simulator.sh - LSP Client Simulation Script
#
# PURPOSE:
#   This script simulates an LSP (Language Server Protocol) client by sending
#   a sequence of JSON-RPC messages to the language server via stdin. It is
#   primarily used for:
#
#   1. GraalVM Native Image Tracing: When run with the native-image-agent,
#      this script exercises all major LSP operations to capture reflection
#      and resource usage for native compilation.
#
#   2. Testing: Validates that the language server responds correctly to
#      standard LSP requests.
#
# USAGE:
#   # Direct execution (for testing)
#   ./lsp-simulator.sh | java -jar language-server.jar
#
#   # With GraalVM tracing agent (for native-image config generation)
#   ./lsp-simulator.sh | java \
#       -agentlib:native-image-agent=config-output-dir=build/native-image-agent \
#       -jar language-server.jar
#
#   # Testing the native binary
#   ./lsp-simulator.sh | ./build/native/nativeCompile/nextflow-lsp
#
# LSP OPERATIONS COVERED:
#   This script exercises the following LSP methods to ensure comprehensive
#   coverage for native image compilation:
#
#   Lifecycle:
#     - initialize         : Establish connection and exchange capabilities
#     - initialized        : Signal client is ready
#     - shutdown           : Request graceful shutdown
#     - exit               : Terminate the server process
#
#   Document Synchronization:
#     - textDocument/didOpen   : Open a document for editing
#     - textDocument/didClose  : Close a document
#
#   Language Features:
#     - textDocument/hover              : Get hover information at position
#     - textDocument/completion         : Get completion suggestions
#     - textDocument/definition         : Go to definition
#     - textDocument/references         : Find all references
#     - textDocument/documentSymbol     : List symbols in document
#     - textDocument/formatting         : Format entire document
#     - textDocument/semanticTokens/full: Get semantic highlighting tokens
#     - textDocument/codeLens           : Get code lens annotations
#     - textDocument/documentLink       : Get clickable links in document
#     - textDocument/rename             : Rename a symbol
#     - textDocument/prepareCallHierarchy: Prepare call hierarchy
#
#   Workspace Features:
#     - workspace/symbol           : Search for symbols across workspace
#     - workspace/didChangeConfiguration : Push client settings (this is what
#                                    initializes the language services)
#     - workspace/executeCommand   : Invoke a server-side command
#
# TEST DOCUMENT:
#   The script opens a sample Nextflow script containing:
#     - A process definition (FOO) with input/output declarations
#     - A workflow block that invokes the process
#   This exercises parsing, symbol resolution, and most LSP features.
#
# NOTE:
#   The sleep delays between messages ensure the server has time to process
#   each request. This is especially important when running with the tracing
#   agent to capture all reflection calls.
#

set -euo pipefail

# =============================================================================
# LSP Message Helper
# =============================================================================
# Sends an LSP message with proper Content-Length header.
# LSP uses a simple HTTP-like protocol with Content-Length header followed
# by the JSON-RPC payload.
#
# Format:
#   Content-Length: <length>\r\n
#   \r\n
#   <json-payload>
#
send_message() {
    local content="$1"
    local length=${#content}
    printf "Content-Length: %d\r\n\r\n%s" "$length" "$content"
}

# =============================================================================
# Test Workspace
# =============================================================================
# A throwaway workspace holding the test document. The server scans the
# workspace root, so this must be a real directory containing a real script.
#
WORKSPACE=$(mktemp -d)
trap 'rm -rf "$WORKSPACE"' EXIT

cat > "$WORKSPACE/main.nf" <<'EOF'
process FOO {
    input:
    val x

    output:
    stdout

    script:
    """
    echo ${x}
    """
}

workflow {
    ch = Channel.of(1, 2, 3)
    FOO(ch)
    FOO.out.view()
}
EOF

WORKSPACE_URI="file://$WORKSPACE"
DOC_URI="$WORKSPACE_URI/main.nf"
# JSON-escape the document so didOpen carries the same text that is on disk
DOC_TEXT=$(sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' "$WORKSPACE/main.nf" | awk '{printf "%s\\n", $0}')

# =============================================================================
# STEP 1: Initialize Connection
# =============================================================================
# The initialize request is sent as the first request from client to server.
# It provides client capabilities and workspace information.
# The server responds with its capabilities.
#
send_message '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":1234,"capabilities":{"textDocument":{"hover":{"contentFormat":["markdown","plaintext"]},"completion":{"completionItem":{"snippetSupport":true}},"definition":{},"references":{},"documentSymbol":{},"formatting":{},"semanticTokens":{"requests":{"full":true}}}},"rootUri":"'"$WORKSPACE_URI"'","workspaceFolders":[{"uri":"'"$WORKSPACE_URI"'","name":"main"}]}}'

sleep 0.5

# =============================================================================
# STEP 2: Initialized Notification
# =============================================================================
# Sent from client to server after receiving the initialize response.
# Signals that the client is ready to receive requests/notifications.
#
send_message '{"jsonrpc":"2.0","method":"initialized","params":{}}'

sleep 0.5

# =============================================================================
# STEP 2b: Configuration Notification
# =============================================================================
# This is what actually initializes the language services and scans the
# workspace -- without it every language feature below returns an empty
# result and the tracing agent sees almost nothing. The values must differ
# from LanguageServerConfiguration.defaults() or the server skips the
# re-initialization (see NextflowLanguageServer.shouldInitialize).
#
send_message '{"jsonrpc":"2.0","method":"workspace/didChangeConfiguration","params":{"settings":{"nextflow":{"debug":false,"errorReportingMode":"paranoid","files":{"exclude":[]},"completion":{"extended":true,"maxItems":100},"formatting":{"harshilAlignment":false,"maheshForm":false,"sortDeclarations":false},"dag":{"direction":"TB","verbose":false}}}}}'

sleep 5

# =============================================================================
# STEP 3: Open a Document
# =============================================================================
# Notifies the server that a document was opened. The server will parse
# the document and may send diagnostics back.
#
send_message '{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"'"$DOC_URI"'","languageId":"nextflow","version":1,"text":"'"$DOC_TEXT"'"}}}'

sleep 3

# =============================================================================
# STEP 4: Hover Request
# =============================================================================
# Request hover information at a specific position (line 14 = `Channel`)
# Returns documentation, type info, or other relevant details.
#
send_message '{"jsonrpc":"2.0","id":2,"method":"textDocument/hover","params":{"textDocument":{"uri":"'"$DOC_URI"'"},"position":{"line":14,"character":9}}}'

sleep 0.5

# =============================================================================
# STEP 5: Completion Request
# =============================================================================
# Request code completion suggestions after the `.` in `FOO.out.view()`.
#
send_message '{"jsonrpc":"2.0","id":3,"method":"textDocument/completion","params":{"textDocument":{"uri":"'"$DOC_URI"'"},"position":{"line":16,"character":12},"context":{"triggerKind":2,"triggerCharacter":"."}}}'

sleep 0.5

# =============================================================================
# STEP 6: Go to Definition
# =============================================================================
# Request the definition location for the FOO process call in the workflow.
#
send_message '{"jsonrpc":"2.0","id":4,"method":"textDocument/definition","params":{"textDocument":{"uri":"'"$DOC_URI"'"},"position":{"line":15,"character":5}}}'

sleep 0.5

# =============================================================================
# STEP 7: Find References
# =============================================================================
# Find all references to a symbol at position (line 0, char 8 = "FOO")
# Includes the declaration itself when includeDeclaration is true.
#
send_message '{"jsonrpc":"2.0","id":5,"method":"textDocument/references","params":{"textDocument":{"uri":"'"$DOC_URI"'"},"position":{"line":0,"character":8},"context":{"includeDeclaration":true}}}'

sleep 0.5

# =============================================================================
# STEP 8: Document Symbols
# =============================================================================
# Request all symbols (processes, workflows, functions, variables) in the document.
# Used for outline view and breadcrumbs in IDEs.
#
send_message '{"jsonrpc":"2.0","id":6,"method":"textDocument/documentSymbol","params":{"textDocument":{"uri":"'"$DOC_URI"'"}}}'

sleep 0.5

# =============================================================================
# STEP 9: Document Formatting
# =============================================================================
# Request to format the entire document according to specified options.
# Returns a list of text edits to apply.
#
send_message '{"jsonrpc":"2.0","id":7,"method":"textDocument/formatting","params":{"textDocument":{"uri":"'"$DOC_URI"'"},"options":{"tabSize":4,"insertSpaces":true}}}'

sleep 0.5

# =============================================================================
# STEP 10: Semantic Tokens
# =============================================================================
# Request semantic tokens for syntax highlighting beyond basic tokenization.
# Provides information about token types (function, variable, type, etc.)
# and modifiers (declaration, definition, readonly, etc.)
#
send_message '{"jsonrpc":"2.0","id":8,"method":"textDocument/semanticTokens/full","params":{"textDocument":{"uri":"'"$DOC_URI"'"}}}'

sleep 0.5

# =============================================================================
# STEP 11: Code Lens
# =============================================================================
# Request code lens annotations - actionable contextual information
# displayed inline with the code (e.g., "Run | Debug" for processes).
#
send_message '{"jsonrpc":"2.0","id":9,"method":"textDocument/codeLens","params":{"textDocument":{"uri":"'"$DOC_URI"'"}}}'

sleep 0.5

# =============================================================================
# STEP 12: Document Links
# =============================================================================
# Request clickable links in the document (URLs, file references, etc.)
# Each link has a target URI and can be resolved on click.
#
send_message '{"jsonrpc":"2.0","id":10,"method":"textDocument/documentLink","params":{"textDocument":{"uri":"'"$DOC_URI"'"}}}'

sleep 0.5

# =============================================================================
# STEP 13: Workspace Symbol Search
# =============================================================================
# Search for symbols across the entire workspace by name.
# Useful for "Go to Symbol in Workspace" functionality.
#
send_message '{"jsonrpc":"2.0","id":11,"method":"workspace/symbol","params":{"query":"FOO"}}'

sleep 0.5

# =============================================================================
# STEP 14: Rename Symbol
# =============================================================================
# Request to rename a symbol at position (line 0, char 8 = "FOO")
# Returns a workspace edit with all changes needed across files.
#
send_message '{"jsonrpc":"2.0","id":12,"method":"textDocument/rename","params":{"textDocument":{"uri":"'"$DOC_URI"'"},"position":{"line":0,"character":8},"newName":"BAR"}}'

sleep 0.5

# =============================================================================
# STEP 15: Prepare Call Hierarchy
# =============================================================================
# Prepare call hierarchy at position (line 0, char 8 = "FOO" process)
# Returns call hierarchy items that can be used for incoming/outgoing calls.
#
send_message '{"jsonrpc":"2.0","id":13,"method":"textDocument/prepareCallHierarchy","params":{"textDocument":{"uri":"'"$DOC_URI"'"},"position":{"line":0,"character":8}}}'

sleep 0.5

# =============================================================================
# STEP 16: Execute Command
# =============================================================================
# Invoke a server-side command. Exercises ExecuteCommandParams, which is
# deserialized on a code path no other message reaches.
#
send_message '{"jsonrpc":"2.0","id":14,"method":"workspace/executeCommand","params":{"command":"nextflow.server.previewDag","arguments":["'"$DOC_URI"'","FOO"]}}'

sleep 0.5

# =============================================================================
# STEP 17: Close Document
# =============================================================================
# Notify server that the document is being closed.
# Server can release associated resources.
#
send_message '{"jsonrpc":"2.0","method":"textDocument/didClose","params":{"textDocument":{"uri":"'"$DOC_URI"'"}}}'

sleep 0.5

# =============================================================================
# STEP 18: Shutdown Request
# =============================================================================
# Request the server to shut down. Server should return success and
# prepare for exit but not terminate yet.
#
# The long wait first lets outstanding async requests complete -- responses
# arrive out of order and `exit` below terminates the process immediately.
#
sleep 20

send_message '{"jsonrpc":"2.0","id":99,"method":"shutdown","params":null}'

sleep 2

# =============================================================================
# STEP 19: Exit Notification
# =============================================================================
# Notify server to exit. This should be sent after shutdown response
# is received. Server terminates after receiving this.
#
send_message '{"jsonrpc":"2.0","method":"exit","params":null}'
