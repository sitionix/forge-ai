package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;

interface CodexClient extends AutoCloseable {

    String execute(CodexTurnRequest request);

    String version();

    JsonNode request(String method, JsonNode params);

    @Override
    void close();
}
