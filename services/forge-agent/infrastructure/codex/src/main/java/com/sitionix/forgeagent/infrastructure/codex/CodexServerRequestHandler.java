package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
interface CodexServerRequestHandler {

    JsonNode handle(String method, JsonNode params);

    static CodexServerRequestHandler unsupported() {
        return (method, params) -> {
            throw new UnsupportedOperationException("Unsupported Codex server request method=" + method);
        };
    }
}
