package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
final class CodexSessionProtocol {

    private final ObjectMapper objectMapper;

    String startDurableThread(final CodexJsonRpcTransport transport,
                              final JsonNode threadStartParams,
                              final Duration timeout) {
        final ObjectNode params = this.requireParams(threadStartParams).deepCopy();
        params.put("ephemeral", false);
        return requireThreadId(transport.request(CodexProtocol.THREAD_START, params, timeout));
    }

    String resumeThread(final CodexJsonRpcTransport transport, final String threadId, final Duration timeout) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("threadId", requireIdentity(threadId, "Codex resume requires a valid threadId"));
        params.put("excludeTurns", true);
        final String resumedThreadId = requireThreadId(transport.request(CodexProtocol.THREAD_RESUME, params, timeout));
        if (!threadId.equals(resumedThreadId)) {
            throw new CodexTransportException(
                    "Codex resume returned thread.id " + resumedThreadId + " for requested " + threadId
            );
        }
        return resumedThreadId;
    }

    String startTurn(final CodexJsonRpcTransport transport, final JsonNode turnStartParams, final Duration timeout) {
        return requireTurnId(transport.request(
                CodexProtocol.TURN_START,
                this.requireParams(turnStartParams),
                timeout
        ));
    }

    static String requireThreadId(final JsonNode payload) {
        return requireResponseIdentity(payload, "thread", "Codex thread response did not include a valid thread.id");
    }

    static String requireTurnId(final JsonNode payload) {
        return requireResponseIdentity(payload, "turn", "Codex turn response did not include a valid turn.id");
    }

    private static String requireResponseIdentity(final JsonNode payload,
                                                  final String envelope,
                                                  final String message) {
        if (payload == null || !payload.isObject()) {
            throw new CodexTransportException(message);
        }
        final JsonNode identity = payload.path(envelope).path("id");
        if (!identity.isTextual() || identity.asText().isBlank()) {
            throw new CodexTransportException(message);
        }
        return identity.asText();
    }

    private static String requireIdentity(final String identity, final String message) {
        if (identity == null || identity.isBlank()) {
            throw new CodexTransportException(message);
        }
        return identity;
    }

    private ObjectNode requireParams(final JsonNode params) {
        if (params == null || !params.isObject()) {
            throw new CodexTransportException("Codex request params must be an object");
        }
        return (ObjectNode) params;
    }
}
