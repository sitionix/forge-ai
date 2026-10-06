package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.llm.LlmProvider;

public record LlmProviderInbound(String providerId,LlmProvider.AuthState authState,String email,String plan,
                                 LlmProvider.Availability availability,String errorCode) {
    public LlmProvider toDomain(){return new LlmProvider(providerId,authState,email,plan,availability,errorCode);}
    @Override public String toString(){return "LlmProviderInbound[redacted]";}
}
