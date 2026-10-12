package com.sitionix.forgeagent.infrastructure.codex;

class CodexRemoteException extends com.sitionix.forgeagent.domain.exception.ForgeAgentException {

    CodexRemoteException(final String method, final String requestId, final Integer code, final String message) {
        super(code == null ? "AGENT_EXECUTOR_FAILED" : code.toString(),
                com.sitionix.forgeagent.domain.model.ExecutionDiagnosticText.summary(
                        "Codex JSON-RPC error method=" + method + " requestId=" + requestId + " code=" + code + " message=" + message));
    }
}
