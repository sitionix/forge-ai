package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

record CodexStructuredNotification(String method, String threadId, String turnId, String itemId, String itemType) {

    private static final Set<String> GLOBAL_METHODS = Set.of("warning", "deprecationNotice", "configWarning", "error");
    private static final Set<String> NESTED_TURN_METHODS = Set.of("turn/started", "turn/completed");

    static CodexStructuredNotification parse(final JsonNode message) {
        if (message == null || !message.isObject()) {
            throw new CodexTransportException("Codex notification was not an object");
        }
        final String method = text(message.path("method"));
        final JsonNode params = message.path("params");
        if (method == null || !params.isObject()) {
            throw new CodexTransportException("Codex notification did not include a valid method and params");
        }
        final String threadId = "thread/started".equals(method)
                ? text(params.path("thread").path("id"))
                : text(params.path("threadId"));
        final String turnId = NESTED_TURN_METHODS.contains(method)
                ? text(params.path("turn").path("id"))
                : text(params.path("turnId"));
        if ((!GLOBAL_METHODS.contains(method) && threadId == null)
                || (requiresTurnIdentity(method) && turnId == null)) {
            throw new CodexTransportException(
                    "Codex notification " + method + " did not include valid thread/turn identity"
            );
        }
        final JsonNode item = params.path("item");
        return new CodexStructuredNotification(
                method,
                threadId,
                turnId,
                firstText(params.path("itemId"), item.path("id")),
                text(item.path("type"))
        );
    }

    private static boolean requiresTurnIdentity(final String method) {
        return method.startsWith("turn/")
                || method.startsWith("item/")
                || "thread/tokenUsage/updated".equals(method)
                || "thread/compacted".equals(method);
    }

    private static String firstText(final JsonNode first, final JsonNode second) {
        final String firstValue = text(first);
        return firstValue == null ? text(second) : firstValue;
    }

    private static String text(final JsonNode value) {
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
