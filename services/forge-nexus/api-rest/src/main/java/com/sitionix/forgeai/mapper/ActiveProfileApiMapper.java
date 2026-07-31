package com.sitionix.forgeai.mapper;

import com.sitionix.forgeai.api.activeprofile.ActiveLlmEffortRequest;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmEffortResponse;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileResponse;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileUpdateRequest;
import com.sitionix.forgeai.api.activeprofile.ActiveProfileResponse;
import com.sitionix.forgeai.api.activeprofile.LlmUsageWindowKindResponse;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.LlmEffort;
import com.sitionix.forgeai.domain.model.activeprofile.LlmUsageWindowKind;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ActiveProfileApiMapper {

    ActiveProfileResponse toResponse(ActiveProfile source);

    ActiveLlmProfileResponse toResponse(ActiveLlmProfileUpdateResult source);

    UpdateActiveLlmProfileCommand toCommand(ActiveLlmProfileUpdateRequest source);

    LlmEffort toDomain(ActiveLlmEffortRequest source);

    ActiveLlmEffortResponse toResponse(LlmEffort source);

    LlmUsageWindowKindResponse toResponse(LlmUsageWindowKind source);
}
