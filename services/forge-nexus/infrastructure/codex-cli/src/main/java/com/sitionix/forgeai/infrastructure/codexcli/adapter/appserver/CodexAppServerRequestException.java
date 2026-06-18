package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import java.util.List;

final class CodexAppServerRequestException extends IllegalStateException {

    CodexAppServerRequestException(final String message) {
        super(message);
    }

    static CodexAppServerRequestException requestError(final String method,
                                                       final String requestId,
                                                       final List<String> paramKeys,
                                                       final Integer errorCode,
                                                       final String errorMessage,
                                                       final String errorData,
                                                       final String stderrTail,
                                                       final Integer exitStatus,
                                                       final boolean initializeSucceeded,
                                                       final boolean initializedSent,
                                                       final String codexVersion,
                                                       final List<String> command) {
        return new CodexAppServerRequestException("Failed Codex app-server request"
                + " method=" + method
                + " id=" + requestId
                + " paramsKeys=" + paramKeys
                + " jsonRpcError.code=" + (errorCode == null ? "" : errorCode)
                + " jsonRpcError.message=" + quote(errorMessage)
                + " jsonRpcError.data=" + quote(errorData)
                + " stderrTail=" + quote(stderrTail)
                + " exitStatus=" + (exitStatus == null ? "" : exitStatus)
                + " initializeSucceeded=" + initializeSucceeded
                + " initializedSent=" + initializedSent
                + " codexVersion=" + quote(codexVersion)
                + " command=" + quote(String.join(" ", command)));
    }

    static CodexAppServerRequestException protocolError(final String method,
                                                        final String requestId,
                                                        final List<String> paramKeys,
                                                        final String reason,
                                                        final String stderrTail,
                                                        final Integer exitStatus,
                                                        final boolean initializeSucceeded,
                                                        final boolean initializedSent,
                                                        final String codexVersion,
                                                        final List<String> command,
                                                        final Throwable cause) {
        final CodexAppServerRequestException exception = new CodexAppServerRequestException("Failed Codex app-server request"
                + " method=" + method
                + " id=" + requestId
                + " paramsKeys=" + paramKeys
                + " reason=" + quote(reason)
                + " stderrTail=" + quote(stderrTail)
                + " exitStatus=" + (exitStatus == null ? "" : exitStatus)
                + " initializeSucceeded=" + initializeSucceeded
                + " initializedSent=" + initializedSent
                + " codexVersion=" + quote(codexVersion)
                + " command=" + quote(String.join(" ", command)));
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    private static String quote(final String value) {
        if (value == null || value.isBlank()) {
            return "\"\"";
        }
        return "\"" + value.replace("\"", "'").replace('\n', ' ').replace('\r', ' ') + "\"";
    }
}
