#!/bin/bash
set -euo pipefail

#
# Build script for GraalVM native image of nf-language-server
# Supports: linux/amd64, linux/arm64, darwin/arm64 (Apple Silicon)
#
# Usage:
#   ./build-native.sh [options]
#
# Options:
#   --skip-test      Skip testing the native binary
#   --skip-package   Skip packaging the binary
#   --help, -h       Show this help message
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output (disabled in CI)
if [[ -t 1 ]] && [[ -z "${CI:-}" ]]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    NC='\033[0m'
else
    RED=''
    GREEN=''
    YELLOW=''
    NC=''
fi

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check for required tools
check_requirements() {
    log_info "Checking requirements..."

    # Check for Java
    if ! command -v java &> /dev/null; then
        log_error "Java is not installed or not in PATH"
        exit 1
    fi

    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [[ "$JAVA_VERSION" -lt 17 ]]; then
        log_error "Java 17+ is required, found version $JAVA_VERSION"
        exit 1
    fi

    # Check for native-image
    if ! command -v native-image &> /dev/null; then
        # Try to find it in JAVA_HOME
        if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_HOME/bin/native-image" ]]; then
            export PATH="$JAVA_HOME/bin:$PATH"
        elif [[ -n "${GRAALVM_HOME:-}" ]] && [[ -x "$GRAALVM_HOME/bin/native-image" ]]; then
            export PATH="$GRAALVM_HOME/bin:$PATH"
            export JAVA_HOME="$GRAALVM_HOME"
        else
            log_error "native-image not found. Please install GraalVM with native-image support."
            log_error "You can use SDKMAN: sdk install java 21.0.1-graal && sdk use java 21.0.1-graal"
            exit 1
        fi
    fi

    # Verify native-image works
    if ! native-image --version &> /dev/null; then
        log_error "native-image is not working correctly"
        exit 1
    fi

    log_info "Using Java: $(java -version 2>&1 | head -1)"
    log_info "Using native-image: $(native-image --version 2>&1 | head -1)"
}

# Detect platform
detect_platform() {
    OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
    ARCH="$(uname -m)"

    case "$ARCH" in
        x86_64|amd64)
            ARCH="amd64"
            ;;
        aarch64|arm64)
            ARCH="arm64"
            ;;
        *)
            log_error "Unsupported architecture: $ARCH"
            exit 1
            ;;
    esac

    case "$OS" in
        linux)
            PLATFORM="linux-$ARCH"
            BINARY_EXT=""
            ;;
        darwin)
            PLATFORM="darwin-$ARCH"
            BINARY_EXT=""
            ;;
        mingw*|msys*|cygwin*)
            PLATFORM="windows-$ARCH"
            BINARY_EXT=".exe"
            ;;
        *)
            log_error "Unsupported OS: $OS"
            exit 1
            ;;
    esac

    log_info "Detected platform: $PLATFORM"
}

# Build native image (includes shadow jar, tracing agent, and native compilation)
build_native_image() {
    log_info "Building native image (this includes JAR build and tracing agent)..."
    ./gradlew nativeCompile --no-configuration-cache --no-daemon

    local BINARY_PATH="build/native/nativeCompile/nf-language-server${BINARY_EXT}"
    if [[ -f "$BINARY_PATH" ]]; then
        log_info "Native image built successfully: $BINARY_PATH"
        ls -lh "$BINARY_PATH"
    else
        log_error "Native image build failed - binary not found"
        exit 1
    fi
}

# Test the native binary
test_native_binary() {
    log_info "Testing native binary..."

    local BINARY_PATH="build/native/nativeCompile/nf-language-server${BINARY_EXT}"
    local OUTPUT

    OUTPUT=$(./lsp-simulator.sh | "$BINARY_PATH" 2>&1 | head -20)

    if echo "$OUTPUT" | grep -q '"id":1,"result"'; then
        log_info "Native binary test passed - LSP initialize succeeded"
    else
        log_error "Native binary test failed"
        echo "$OUTPUT"
        exit 1
    fi
}

# Package the binary
package_binary() {
    log_info "Packaging binary for $PLATFORM..."

    local BINARY_PATH="build/native/nativeCompile/nf-language-server${BINARY_EXT}"
    local DIST_DIR="build/dist"
    local ARCHIVE_NAME="nf-language-server-$PLATFORM"

    mkdir -p "$DIST_DIR"

    if [[ "$OS" == "darwin" ]] || [[ "$OS" == "linux" ]]; then
        tar -czvf "$DIST_DIR/$ARCHIVE_NAME.tar.gz" -C "build/native/nativeCompile" "nf-language-server${BINARY_EXT}"
        log_info "Created: $DIST_DIR/$ARCHIVE_NAME.tar.gz"
    else
        # Windows - create zip
        (cd "build/native/nativeCompile" && zip -r "../../../$DIST_DIR/$ARCHIVE_NAME.zip" "nf-language-server${BINARY_EXT}")
        log_info "Created: $DIST_DIR/$ARCHIVE_NAME.zip"
    fi
}

# Main build process
main() {
    local SKIP_TEST=false
    local SKIP_PACKAGE=false

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --skip-test)
                SKIP_TEST=true
                shift
                ;;
            --skip-package)
                SKIP_PACKAGE=true
                shift
                ;;
            --help|-h)
                echo "Usage: $0 [options]"
                echo ""
                echo "Build GraalVM native image for nf-language-server"
                echo ""
                echo "Options:"
                echo "  --skip-test     Skip testing the native binary"
                echo "  --skip-package  Skip packaging the binary"
                echo "  --help, -h      Show this help message"
                echo ""
                echo "Requirements:"
                echo "  - GraalVM 21+ with native-image"
                echo "  - Use SDKMAN: sdk install java 21.0.1-graal"
                exit 0
                ;;
            *)
                log_error "Unknown option: $1"
                exit 1
                ;;
        esac
    done

    log_info "Starting native image build..."
    echo ""

    check_requirements
    detect_platform
    build_native_image

    if [[ "$SKIP_TEST" != "true" ]]; then
        test_native_binary
    fi

    if [[ "$SKIP_PACKAGE" != "true" ]]; then
        package_binary
    fi

    echo ""
    log_info "Build completed successfully!"
    log_info "Binary: build/native/nativeCompile/nf-language-server${BINARY_EXT}"
    if [[ "$SKIP_PACKAGE" != "true" ]]; then
        log_info "Package: build/dist/nf-language-server-$PLATFORM.tar.gz"
    fi
}

main "$@"
