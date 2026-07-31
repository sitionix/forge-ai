package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileRequest;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PutExchange;

@HttpExchange(
        accept = MediaType.APPLICATION_JSON_VALUE,
        contentType = MediaType.APPLICATION_JSON_VALUE
)
public interface KnowledgeActiveProfileHttpClient {

    @GetExchange("/api/v1/knowledge/active-profile")
    KnowledgeActiveProfileResponse getActiveProfile();

    @PutExchange("/api/v1/knowledge/active-profile/llm-profile")
    KnowledgeActiveLlmProfileResponse updateActiveLlmProfile(@RequestBody KnowledgeActiveLlmProfileRequest request);
}
