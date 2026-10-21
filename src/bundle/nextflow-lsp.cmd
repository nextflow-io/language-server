@echo off
rem Launcher for the Nextflow language server.
rem
rem Usage:
rem   nextflow-lsp            start the server on stdio
rem   nextflow-lsp --train    no-op (there is no AOT cache on Windows yet)
rem
rem Environment:
rem   NXF_LSP_JAVA_OPTS       extra JVM options
rem
rem stdout is the LSP channel, so this script must not write to it,
rem and all JVM messages go to stderr.

setlocal

if "%~1"=="--train" (
    echo The AOT cache is not supported on Windows, nothing to train 1>&2
    exit /b 0
)

"%~dp0..\runtime\bin\java.exe" -XX:+UseCompressedOops -XX:+DisplayVMOutputToStderr -Xlog:disable -Xlog:all=warning:stderr %NXF_LSP_JAVA_OPTS% -jar "%~dp0..\lib\language-server-all.jar" %*
exit /b %ERRORLEVEL%
