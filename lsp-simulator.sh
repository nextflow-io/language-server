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
#   ./lsp-simulator.sh | ./build/native/nativeCompile/nf-language-server
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
#     - workspace/symbol    : Search for symbols across workspace
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
# STEP 1: Initialize Connection
# =============================================================================
# The initialize request is sent as the first request from client to server.
# It provides client capabilities and workspace information.
# The server responds with its capabilities.
#
send_message '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":1234,"capabilities":{"textDocument":{"hover":{"contentFormat":["markdown","plaintext"]},"completion":{"completionItem":{"snippetSupport":true}},"definition":{},"references":{},"documentSymbol":{},"formatting":{},"semanticTokens":{"requests":{"full":true}}}},"rootUri":"file:///Users/pditommaso/Projects/language-server","workspaceFolders":[{"uri":"file:///Users/pditommaso/Projects/language-server","name":"language-server"}]}}'

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
# STEP 3: Open a Document
# =============================================================================
# Notifies the server that a document was opened. The server will parse
# the document and may send diagnostics back.
#
# Test document: A simple Nextflow script with a process and workflow
#
send_message '{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf","languageId":"nextflow","version":1,"text":"#!/usr/bin/env nextflow\n\nprocess FOO {\n    input:\n    val x\n\n    output:\n    val y\n\n    script:\n    \"\"\"\n    echo hello\n    \"\"\"\n}\n\nworkflow {\n    FOO(Channel.of(1,2,3))\n}\n"}}}'

sleep 0.5

# =============================================================================
# STEP 4: Hover Request
# =============================================================================
# Request hover information at a specific position (line 3, char 4 = "input")
# Returns documentation, type info, or other relevant details.
#
send_message '{"jsonrpc":"2.0","id":2,"method":"textDocument/hover","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf"},"position":{"line":3,"character":4}}}'

sleep 0.5

# =============================================================================
# STEP 5: Completion Request
# =============================================================================
# Request code completion suggestions at a position (line 17, char 8)
# This is inside the workflow block where process calls are made.
#
send_message '{"jsonrpc":"2.0","id":3,"method":"textDocument/completion","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf"},"position":{"line":17,"character":8}}}'

sleep 0.5

# =============================================================================
# STEP 6: Go to Definition
# =============================================================================
# Request the definition location for a symbol at position (line 17, char 4)
# This points to the FOO process call in the workflow.
#
send_message '{"jsonrpc":"2.0","id":4,"method":"textDocument/definition","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf"},"position":{"line":17,"character":4}}}'

sleep 0.5

# =============================================================================
# STEP 7: Find References
# =============================================================================
# Find all references to a symbol at position (line 2, char 8 = "FOO")
# Includes the declaration itself when includeDeclaration is true.
#
send_message '{"jsonrpc":"2.0","id":5,"method":"textDocument/references","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf"},"position":{"line":2,"character":8},"context":{"includeDeclaration":true}}}'

sleep 0.5

# =============================================================================
# STEP 8: Document Symbols
# =============================================================================
# Request all symbols (processes, workflows, functions, variables) in the document.
# Used for outline view and breadcrumbs in IDEs.
#
send_message '{"jsonrpc":"2.0","id":6,"method":"textDocument/documentSymbol","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf"}}}'

sleep 0.5

# =============================================================================
# STEP 9: Document Formatting
# =============================================================================
# Request to format the entire document according to specified options.
# Returns a list of text edits to apply.
#
send_message '{"jsonrpc":"2.0","id":7,"method":"textDocument/formatting","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf"},"options":{"tabSize":4,"insertSpaces":true}}}'

sleep 0.5

# =============================================================================
# STEP 10: Semantic Tokens
# =============================================================================
# Request semantic tokens for syntax highlighting beyond basic tokenization.
# Provides information about token types (function, variable, type, etc.)
# and modifiers (declaration, definition, readonly, etc.)
#
send_message '{"jsonrpc":"2.0","id":8,"method":"textDocument/semanticTokens/full","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf"}}}'

sleep 0.5

# =============================================================================
# STEP 11: Code Lens
# =============================================================================
# Request code lens annotations - actionable contextual information
# displayed inline with the code (e.g., "Run | Debug" for processes).
#
send_message '{"jsonrpc":"2.0","id":9,"method":"textDocument/codeLens","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf"}}}'

sleep 0.5

# =============================================================================
# STEP 12: Document Links
# =============================================================================
# Request clickable links in the document (URLs, file references, etc.)
# Each link has a target URI and can be resolved on click.
#
send_message '{"jsonrpc":"2.0","id":10,"method":"textDocument/documentLink","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf"}}}'

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
# Request to rename a symbol at position (line 2, char 8 = "FOO")
# Returns a workspace edit with all changes needed across files.
#
send_message '{"jsonrpc":"2.0","id":12,"method":"textDocument/rename","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf"},"position":{"line":2,"character":8},"newName":"BAR"}}'

sleep 0.5

# =============================================================================
# STEP 15: Prepare Call Hierarchy
# =============================================================================
# Prepare call hierarchy at position (line 2, char 8 = "FOO" process)
# Returns call hierarchy items that can be used for incoming/outgoing calls.
#
send_message '{"jsonrpc":"2.0","id":13,"method":"textDocument/prepareCallHierarchy","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf"},"position":{"line":2,"character":8}}}'

sleep 0.5

# =============================================================================
# STEP 16: Close Document
# =============================================================================
# Notify server that the document is being closed.
# Server can release associated resources.
#
send_message '{"jsonrpc":"2.0","method":"textDocument/didClose","params":{"textDocument":{"uri":"file:///Users/pditommaso/Projects/language-server/test.nf"}}}'

sleep 0.5

# =============================================================================
# STEP 17: Shutdown Request
# =============================================================================
# Request the server to shut down. Server should return success and
# prepare for exit but not terminate yet.
#
send_message '{"jsonrpc":"2.0","id":99,"method":"shutdown","params":null}'

sleep 0.5

# =============================================================================
# STEP 18: Exit Notification
# =============================================================================
# Notify server to exit. This should be sent after shutdown response
# is received. Server terminates after receiving this.
#
send_message '{"jsonrpc":"2.0","method":"exit","params":null}'
