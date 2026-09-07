package com.sitionix.forgeagent.domain.model;

public enum NodeContextMode {
    FRESH_EACH_NODE_RUN,
    REUSE_WITHIN_WORKFLOW_NODE;

    public static NodeContextMode legacyDefault(final NodeContextMode value) {
        return value == null ? FRESH_EACH_NODE_RUN : value;
    }
}
