package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileResponse;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileUpdateRequest;
import com.sitionix.forgeai.api.activeprofile.ActiveProfileResponse;
import com.sitionix.forgeai.domain.usecase.GetActiveProfile;
import com.sitionix.forgeai.domain.usecase.UpdateActiveLlmProfile;
import com.sitionix.forgeai.mapper.ActiveProfileApiMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class KnowledgeActiveProfileController {

    private final GetActiveProfile getActiveProfile;
    private final UpdateActiveLlmProfile updateActiveLlmProfile;
    private final ActiveProfileApiMapper activeProfileApiMapper;

    @GetMapping("/api/v1/infrastructure/knowledge/active-profile")
    public ResponseEntity<ActiveProfileResponse> getActiveProfile() {
        return ResponseEntity.ok(this.activeProfileApiMapper.toResponse(this.getActiveProfile.execute()));
    }

    @PutMapping("/api/v1/infrastructure/knowledge/active-profile/llm-profile")
    public ResponseEntity<ActiveLlmProfileResponse> updateActiveLlmProfile(
            @RequestBody final ActiveLlmProfileUpdateRequest request
    ) {
        return ResponseEntity.ok(this.activeProfileApiMapper.toResponse(
                this.updateActiveLlmProfile.execute(this.activeProfileApiMapper.toCommand(request))
        ));
    }
}
