package com.sitionix.forgeagent.infrastructure.codex;

class CodexRemoteException extends RuntimeException {

    CodexRemoteException(final String method, final String requestId, final Integer code, final String message) {
        super("Codex JSON-RPC error method=" + method + " requestId=" + requestId + " code=" + code + " message=" + message);
    }
}
