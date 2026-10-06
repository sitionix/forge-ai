package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.llm.*;
import java.util.List;
import java.util.UUID;

public interface ManageLlmAuthorization {
    List<LlmProvider> providers();
    LlmLogin startLogin(String browserBinding);
    LlmLogin readLogin(UUID id,String browserBinding);
    LlmLogin cancelLogin(UUID id,String browserBinding);
    LlmProvider logout();
}
