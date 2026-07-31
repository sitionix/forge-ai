package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileRequest;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "knowledgeActiveProfileFeignClient",
        url = "${forge.ai.infrastructure.knowledge.base-url}",
        configuration = KnowledgeActiveProfileFeignConfiguration.class
)
interface KnowledgeActiveProfileFeignClient {

    @GetMapping("/api/v1/knowledge/active-profile")
    KnowledgeActiveProfileResponse getActiveProfile();

    @PutMapping("/api/v1/knowledge/active-profile/llm-profile")
    KnowledgeActiveLlmProfileResponse updateActiveLlmProfile(@RequestBody KnowledgeActiveLlmProfileRequest request);
}
