package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;

interface CodexRpcClient extends AutoCloseable {

    String version();

    JsonNode request(String method, JsonNode params);

    @Override
    void close();
}
