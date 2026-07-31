package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.LlmUsageWindowKind;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileRequest;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeLlmUsageWindowKind;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface KnowledgeActiveProfileClientMapper {

    ActiveProfile toDomain(KnowledgeActiveProfileResponse source);

    ActiveLlmProfileUpdateResult toDomain(KnowledgeActiveLlmProfileResponse source);

    KnowledgeActiveLlmProfileRequest toRequest(UpdateActiveLlmProfileCommand source);

    LlmUsageWindowKind toDomain(KnowledgeLlmUsageWindowKind source);
}
