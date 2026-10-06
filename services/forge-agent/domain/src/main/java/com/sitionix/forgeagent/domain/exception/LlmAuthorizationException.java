package com.sitionix.forgeagent.domain.exception;

/** Safe installation authorization failure; never carries a provider response or credentials. */
public final class LlmAuthorizationException extends ForgeAgentException {
    public LlmAuthorizationException(String code) { super(code, code); }
    public static LlmAuthorizationException find(Throwable failure) {
        for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause())
            if (failure instanceof LlmAuthorizationException authorization) return authorization;
        return null;
    }
}
