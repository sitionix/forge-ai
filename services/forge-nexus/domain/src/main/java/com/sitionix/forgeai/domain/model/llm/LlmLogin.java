package com.sitionix.forgeai.domain.model.llm;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record LlmLogin(UUID loginId, Status status, Instant expiresAt, String authUrl, String errorCode) {
    public enum Status { PENDING, COMPLETED, FAILED, CANCELLED, EXPIRED }
    public LlmLogin {
        if(loginId==null || status==null || expiresAt==null || !LlmAuthorizationFailure.safe(errorCode))invalid();
        if(status!=Status.PENDING) authUrl=null;
        else {
            try {
                var url=URI.create(authUrl);
                if(authUrl.length()>8192 || authUrl.chars().anyMatch(Character::isISOControl)
                        || !"https".equals(url.getScheme()) || !("auth.openai.com".equals(url.getHost()) || "auth0.openai.com".equals(url.getHost()))
                        || url.getRawUserInfo()!=null || url.getRawFragment()!=null || !(url.getPort()==-1 || url.getPort()==443)) invalid();
            } catch(RuntimeException exception){invalid();}
        }
    }
    private static void invalid(){throw new LlmAuthorizationFailure("CODEX_AUTH_UNAVAILABLE");}
    @Override public String toString(){return "LlmLogin[redacted]";}
}
