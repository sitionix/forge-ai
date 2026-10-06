package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.llm.*;
import java.util.List;
import java.util.UUID;

public interface ForgeAgentLlmClient {
    List<LlmProvider> providers();
    LlmLogin startLogin(String browserBinding);
    LlmLogin readLogin(UUID id,String browserBinding);
    LlmLogin cancelLogin(UUID id,String browserBinding);
    LlmProvider logout();
}
