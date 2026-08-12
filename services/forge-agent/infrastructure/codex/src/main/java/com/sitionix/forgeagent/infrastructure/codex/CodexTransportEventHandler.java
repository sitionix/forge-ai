package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;

interface CodexTransportEventHandler {

    void handleNotification(String method, JsonNode params);

    void transportFailed(RuntimeException exception);

    static CodexTransportEventHandler noop() {
        return new CodexTransportEventHandler() {
            @Override
            public void handleNotification(final String method, final JsonNode params) {
            }

            @Override
            public void transportFailed(final RuntimeException exception) {
            }
        };
    }
}
