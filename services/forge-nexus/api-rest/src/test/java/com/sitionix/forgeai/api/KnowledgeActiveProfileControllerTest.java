package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileDetailsResponse;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileResponse;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileUpdateRequest;
import com.sitionix.forgeai.api.activeprofile.ActiveProfileResponse;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfile;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.domain.usecase.GetActiveProfile;
import com.sitionix.forgeai.domain.usecase.UpdateActiveLlmProfile;
import com.sitionix.forgeai.mapper.ActiveProfileApiMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeActiveProfileControllerTest {

    @Mock
    private GetActiveProfile getActiveProfile;

    @Mock
    private UpdateActiveLlmProfile updateActiveLlmProfile;

    @Mock
    private ActiveProfileApiMapper mapper;

    private KnowledgeActiveProfileController controller;

    @BeforeEach
    void setUp() {
        this.controller = new KnowledgeActiveProfileController(
                this.getActiveProfile,
                this.updateActiveLlmProfile,
                this.mapper
        );
    }

    @Test
    void getDelegatesAndMapsResponse() {
        final ActiveProfile profile = new ActiveProfile(1, new ActiveLlmProfile("ollama", "qwen", null), null);
        final ActiveProfileResponse response = new ActiveProfileResponse(
                1,
                new ActiveLlmProfileDetailsResponse("ollama", "qwen", null),
                null
        );
        when(this.getActiveProfile.execute()).thenReturn(profile);
        when(this.mapper.toResponse(profile)).thenReturn(response);

        final ResponseEntity<ActiveProfileResponse> result = this.controller.getActiveProfile();

        assertThat(result.getBody()).isSameAs(response);
        verify(this.getActiveProfile).execute();
        verify(this.mapper).toResponse(profile);
    }

    @Test
    void putMapsRequestDelegatesAndMapsResponse() {
        final ActiveLlmProfileUpdateRequest request = new ActiveLlmProfileUpdateRequest(3L, "ollama", "qwen", null);
        final UpdateActiveLlmProfileCommand command = new UpdateActiveLlmProfileCommand(3, "ollama", "qwen", null);
        final ActiveLlmProfileUpdateResult update = new ActiveLlmProfileUpdateResult(
                4,
                new ActiveLlmProfile("ollama", "qwen", null)
        );
        final ActiveLlmProfileResponse response = new ActiveLlmProfileResponse(
                4,
                new ActiveLlmProfileDetailsResponse("ollama", "qwen", null)
        );
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.updateActiveLlmProfile.execute(command)).thenReturn(update);
        when(this.mapper.toResponse(update)).thenReturn(response);

        final ResponseEntity<ActiveLlmProfileResponse> result = this.controller.updateActiveLlmProfile(request);

        assertThat(result.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.updateActiveLlmProfile).execute(command);
        verify(this.mapper).toResponse(update);
    }
}
