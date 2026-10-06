package com.sitionix.forgeai.infrastructure.agentclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.exception.AgentClientException;
import com.sitionix.forgeai.domain.model.llm.*;
import com.sitionix.forgeai.domain.port.ForgeAgentLlmClient;
import com.sitionix.forgeai.infrastructure.agentclient.dto.LlmBindingOutbound;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class ForgeAgentLlmClientAdapter implements ForgeAgentLlmClient {
    private final ForgeAgentHttpClient http;
    private final ForgeAgentClientCallExecutor executor;
    private final ObjectMapper mapper;
    public ForgeAgentLlmClientAdapter(ForgeAgentHttpClient http,ForgeAgentClientCallExecutor executor,ObjectMapper mapper){this.http=http;this.executor=executor;this.mapper=mapper;}
    public List<LlmProvider> providers(){return safe(()->{var result=executor.execute(http::llmProviders);
        if(result==null || result.size()!=1)throw new LlmAuthorizationFailure("CODEX_AUTH_UNAVAILABLE");
        return result.stream().map(r->r.toDomain()).toList();});}
    public LlmLogin startLogin(String binding){return safe(()->executor.execute(()->http.startLlmLogin(new LlmBindingOutbound(binding))).toDomain());}
    public LlmLogin readLogin(UUID id,String binding){return safe(()->executor.execute(()->http.readLlmLogin(id,new com.sitionix.forgeai.infrastructure.agentclient.dto.LlmBindingHeader(binding))).toDomain());}
    public LlmLogin cancelLogin(UUID id,String binding){return safe(()->executor.execute(()->http.cancelLlmLogin(id,new LlmBindingOutbound(binding))).toDomain());}
    public LlmProvider logout(){return safe(()->executor.execute(()->http.logoutLlm(Map.of())).toDomain());}
    private <T> T safe(Supplier<T> operation){
        try{return operation.get();}
        catch(LlmAuthorizationFailure failure){throw failure;}
        catch(AgentClientException failure){
            String code=null;
            try{var error=mapper.readValue(failure.responseBody(),SafeError.class);code=error.code();}catch(Exception ignored) { }
            // Restrict status/code pairs, so malformed upstream responses cannot impersonate client errors.
            boolean accepted=code!=null && LlmAuthorizationFailure.safe(code) && (failure.statusCode()==404 && code.equals("LOGIN_NOT_FOUND")
                    || failure.statusCode()==400 && code.equals("INVALID_BROWSER_BINDING")
                    || failure.statusCode()==409 && Set.of("LOGIN_IN_PROGRESS","CODEX_LOGOUT_IN_PROGRESS","CODEX_AUTH_REQUIRED").contains(code)
                    || failure.statusCode()==503 && code.startsWith("CODEX_"));
            throw new LlmAuthorizationFailure(accepted ? code : "CODEX_AUTH_UNAVAILABLE");
        } catch(RuntimeException failure){throw new LlmAuthorizationFailure("CODEX_AUTH_UNAVAILABLE");}
    }
    private record SafeError(String code,String message,String correlationId){@Override public String toString(){return "SafeError[redacted]";}}
}
