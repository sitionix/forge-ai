package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.runtime.ProviderTurnRecoveryResult;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryTerminalOutcome;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class CodexRecoveryProtocol {

    private static final int PAGE_LIMIT = 100;
    private static final int MAX_PAGES = 100;

    private final ObjectMapper objectMapper;

    CodexRecoveryProtocol(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ProviderTurnRecoveryResult inspectTurn(final CodexJsonRpcTransport transport, final String threadId,
                                           final String turnId, final Duration timeout) {
        if (transport == null || isBlank(threadId) || isBlank(turnId) || timeout == null
                || timeout.isZero() || timeout.isNegative()) {
            return ProviderTurnRecoveryResult.unknown("Codex recovery inspection identity or timeout is invalid");
        }
        try {
            return this.inspectPages(transport, threadId, turnId, timeout);
        } catch (final RuntimeException exception) {
            return ProviderTurnRecoveryResult.unknown("Codex recovery inspection failed: "
                    + exception.getClass().getSimpleName());
        }
    }

    private ProviderTurnRecoveryResult inspectPages(final CodexJsonRpcTransport transport, final String threadId,
                                                    final String turnId, final Duration timeout) {
        final Set<String> observedCursors = new HashSet<>();
        String cursor = null;
        String targetStatus = null;
        int targetMatches = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            final JsonNode response = transport.request(
                    CodexProtocol.THREAD_TURNS_LIST, this.params(threadId, cursor), timeout);
            if (response == null || !response.isObject() || !response.path("data").isArray()) {
                return ProviderTurnRecoveryResult.unknown("Codex turns list response was malformed");
            }
            for (final JsonNode turn : response.path("data")) {
                if (!turn.isObject() || !turn.path("id").isTextual() || isBlank(turn.path("id").asText())) {
                    return ProviderTurnRecoveryResult.unknown("Codex turns list contained an invalid turn identity");
                }
                if (!turn.path("items").isArray()) {
                    return ProviderTurnRecoveryResult.unknown("Codex turns list contained invalid turn items");
                }
                if (!turn.path("status").isTextual() || !isRecognizedStatus(turn.path("status").asText())) {
                    return ProviderTurnRecoveryResult.unknown("Codex turns list contained an invalid turn status");
                }
                if (turnId.equals(turn.path("id").asText())) {
                    targetMatches++;
                    targetStatus = turn.path("status").asText();
                }
            }
            final JsonNode nextCursor = response.get("nextCursor");
            if (nextCursor == null || nextCursor.isNull()) {
                return this.classify(targetMatches, targetStatus);
            }
            if (!nextCursor.isTextual() || isBlank(nextCursor.asText())) {
                return ProviderTurnRecoveryResult.unknown("Codex turns list cursor was malformed");
            }
            cursor = nextCursor.asText();
            if (!observedCursors.add(cursor)) {
                return ProviderTurnRecoveryResult.unknown("Codex turns list cursor repeated");
            }
        }
        return ProviderTurnRecoveryResult.unknown("Codex turns list exceeded the pagination bound");
    }

    private ObjectNode params(final String threadId, final String cursor) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("threadId", threadId);
        if (cursor != null) {
            params.put("cursor", cursor);
        }
        params.put("limit", PAGE_LIMIT);
        params.put("sortDirection", "desc");
        params.put("itemsView", "notLoaded");
        return params;
    }

    private ProviderTurnRecoveryResult classify(final int matches, final String status) {
        if (matches != 1) {
            return ProviderTurnRecoveryResult.unknown(matches == 0
                    ? "Codex target turn was not found"
                    : "Codex target turn identity was ambiguous");
        }
        return switch (status) {
            case "completed" -> ProviderTurnRecoveryResult.terminal(
                    ProviderTurnRecoveryTerminalOutcome.SUCCEEDED, "Codex turn status completed");
            case "failed" -> ProviderTurnRecoveryResult.terminal(
                    ProviderTurnRecoveryTerminalOutcome.FAILED, "Codex turn status failed");
            case "interrupted" -> ProviderTurnRecoveryResult.terminal(
                    ProviderTurnRecoveryTerminalOutcome.CANCELLED, "Codex turn status interrupted");
            case "inProgress" -> ProviderTurnRecoveryResult.active("Codex turn status inProgress");
            default -> ProviderTurnRecoveryResult.unknown("Codex target turn status was unknown: " + status);
        };
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    private static boolean isRecognizedStatus(final String status) {
        return "completed".equals(status)
                || "failed".equals(status)
                || "interrupted".equals(status)
                || "inProgress".equals(status);
    }
}
