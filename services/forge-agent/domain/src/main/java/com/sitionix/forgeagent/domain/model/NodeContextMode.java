package com.sitionix.forgeagent.domain.model;

public enum NodeContextMode {
    FRESH_EACH_NODE_RUN,
    REUSE_WITHIN_WORKFLOW_NODE,
    REUSE_WITHIN_WORKFLOW_ITERATION;

    public boolean reusable() { return this != FRESH_EACH_NODE_RUN; }

    public static NodeContextMode legacyDefault(final NodeContextMode value) {
        return value == null ? FRESH_EACH_NODE_RUN : value;
    }
}
