package com.sitionix.forgeai.api.llm;

import com.sitionix.forgeai.domain.model.llm.*;
import java.time.Instant;
import java.util.UUID;

public final class LlmAuthorizationResponses {
    private LlmAuthorizationResponses(){}
    public record Provider(String providerId,LlmProvider.AuthState authState,String email,String plan,LlmProvider.Availability availability,String errorCode){
        public static Provider from(LlmProvider provider){return new Provider(provider.providerId(),provider.authState(),provider.email(),provider.plan(),provider.availability(),provider.errorCode());}
        @Override public String toString(){return "LlmProviderResponse[redacted]";}
    }
    public record Login(UUID loginId,LlmLogin.Status status,Instant expiresAt,String authUrl,String errorCode){
        public static Login from(LlmLogin login){return new Login(login.loginId(),login.status(),login.expiresAt(),login.authUrl(),login.errorCode());}
        @Override public String toString(){return "LlmLoginResponse[redacted]";}
    }
}
