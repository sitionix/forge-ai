package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.llm.LlmLogin;
import java.time.Instant;
import java.util.UUID;

public record LlmLoginInbound(UUID loginId,LlmLogin.Status status,Instant expiresAt,String authUrl,String errorCode) {
    public LlmLogin toDomain(){return new LlmLogin(loginId,status,expiresAt,authUrl,errorCode);}
    @Override public String toString(){return "LlmLoginInbound[redacted]";}
}
