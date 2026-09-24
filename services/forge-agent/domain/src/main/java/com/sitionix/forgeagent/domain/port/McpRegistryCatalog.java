package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.McpAvailablePage;

public interface McpRegistryCatalog {
    McpAvailablePage list(String search, String cursor, int limit);
}
