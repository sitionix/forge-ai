package com.sitionix.forgeai.domain.model.llm;

public record LlmProvider(String providerId, AuthState authState, String email, String plan,
                          Availability availability, String errorCode) {
    public enum AuthState { SIGNED_OUT, CONNECTING, CONNECTED, ERROR }
    public enum Availability { AVAILABLE, UNAVAILABLE }
    public LlmProvider {
        if (!"codex".equals(providerId) || authState == null || availability == null
                || !LlmAuthorizationFailure.safe(errorCode) || !bounded(email,320) || !bounded(plan,100))
            throw new LlmAuthorizationFailure("CODEX_AUTH_UNAVAILABLE");
    }
    private static boolean bounded(String value,int max) {return value==null || value.length()<=max && value.chars().noneMatch(Character::isISOControl);}
    @Override public String toString(){return "LlmProvider[redacted]";}
}
