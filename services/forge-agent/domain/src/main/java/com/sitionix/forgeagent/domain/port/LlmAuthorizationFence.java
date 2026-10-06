package com.sitionix.forgeagent.domain.port;

/** Durable installation revocation only; contains no account or provider credentials. */
public interface LlmAuthorizationFence {
    enum Status { UNINITIALIZED, BLOCKED, APPROVED }
    Status status();
    void block();
    void clear();

    /** Explicit standalone fixtures only. Production injects the protected file-backed fence. */
    static LlmAuthorizationFence ephemeral() {
        return new LlmAuthorizationFence() {
            private boolean blocked;
            public Status status() { return blocked ? Status.BLOCKED : Status.APPROVED; }
            public void block() { blocked = true; }
            public void clear() { blocked = false; }
        };
    }
}
