package com.sitionix.forgeai.application.agentproxy;

import com.sitionix.forgeai.domain.model.llm.*;
import com.sitionix.forgeai.domain.port.ForgeAgentLlmClient;
import com.sitionix.forgeai.domain.usecase.ManageLlmAuthorization;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LlmAuthorizationUseCase implements ManageLlmAuthorization {
    private final ForgeAgentLlmClient client;
    public LlmAuthorizationUseCase(ForgeAgentLlmClient client){this.client=client;}
    public List<LlmProvider> providers(){return client.providers();}
    public LlmLogin startLogin(String binding){return client.startLogin(binding);}
    public LlmLogin readLogin(UUID id,String binding){return client.readLogin(id,binding);}
    public LlmLogin cancelLogin(UUID id,String binding){return client.cancelLogin(id,binding);}
    public LlmProvider logout(){return client.logout();}
}
