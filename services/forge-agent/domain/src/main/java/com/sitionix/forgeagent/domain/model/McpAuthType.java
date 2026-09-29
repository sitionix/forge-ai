package com.sitionix.forgeagent.domain.model;

public enum McpAuthType {
    NONE, BEARER, SECRET_HEADERS, OAUTH;
    public McpAuthType protocolType() { return this==OAUTH ? BEARER : this; }
}
