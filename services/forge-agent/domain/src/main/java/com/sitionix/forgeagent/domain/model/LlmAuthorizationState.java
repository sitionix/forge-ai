package com.sitionix.forgeagent.domain.model;

/** Account display state. CONNECTED from a cached read is not proof of inference access. */
public record LlmAuthorizationState(String providerId, AuthState authState, long generation,
                                    String email, String plan, Availability availability, String errorCode) {
    public enum AuthState { SIGNED_OUT, CONNECTING, CONNECTED, ERROR }
    public enum Availability { AVAILABLE, UNAVAILABLE }
}
