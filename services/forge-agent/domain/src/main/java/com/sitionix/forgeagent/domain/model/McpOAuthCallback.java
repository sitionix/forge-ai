package com.sitionix.forgeagent.domain.model;

/** Never serialized as a public result or included in exception output. */
public final class McpOAuthCallback {
    private final String state, browserBinding, code, error, issuer;
    public McpOAuthCallback(String state, String browserBinding, String code, String error, String issuer) {
        this.state = state; this.browserBinding = browserBinding; this.code = code; this.error = error; this.issuer = issuer;
    }
    public String state() { return state; }
    public String browserBinding() { return browserBinding; }
    public String code() { return code; }
    public String error() { return error; }
    public String issuer() { return issuer; }
    @Override public String toString() { return "McpOAuthCallback[redacted]"; }
}
