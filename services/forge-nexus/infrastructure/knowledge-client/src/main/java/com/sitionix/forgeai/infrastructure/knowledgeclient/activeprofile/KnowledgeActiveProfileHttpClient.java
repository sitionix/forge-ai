package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileRequest;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PutExchange;

@HttpExchange(accept = MediaType.APPLICATION_JSON_VALUE)
public interface KnowledgeActiveProfileHttpClient {

    @GetExchange("/api/v1/knowledge/active-profile")
    KnowledgeActiveProfileResponse getActiveProfile();

    @PutExchange(value = "/api/v1/knowledge/active-profile/llm-profile", contentType = MediaType.APPLICATION_JSON_VALUE)
    KnowledgeActiveLlmProfileResponse updateActiveLlmProfile(@RequestBody KnowledgeActiveLlmProfileRequest request);
}
