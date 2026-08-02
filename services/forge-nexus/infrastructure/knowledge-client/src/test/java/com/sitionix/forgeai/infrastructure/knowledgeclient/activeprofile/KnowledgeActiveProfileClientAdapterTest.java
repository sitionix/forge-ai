package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfile;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmSelection;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileDetails;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileRequest;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmSelection;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeActiveProfileClientAdapterTest {

    @Mock
    private KnowledgeActiveProfileHttpClient httpClient;

    @Mock
    private KnowledgeActiveProfileClientMapper mapper;

    @Mock
    private KnowledgeClientCallExecutor clientCallExecutor;

    private KnowledgeActiveProfileClientAdapter adapter;

    @BeforeEach
    void setUp() {
        this.adapter = new KnowledgeActiveProfileClientAdapter(
                this.httpClient,
                this.mapper,
                this.clientCallExecutor
        );
    }

    @Test
    void getCallsExecutorAndMapsResponse() {
        // given
        final KnowledgeActiveProfileResponse clientResponse =
                new KnowledgeActiveProfileResponse(1L, details(), null);
        final ActiveProfile domain =
                new ActiveProfile(1, new ActiveLlmProfile("ollama", "qwen", null), null);
        when(this.clientCallExecutor.<KnowledgeActiveProfileResponse>execute(any()))
                .thenReturn(clientResponse);
        when(this.mapper.toDomain(clientResponse)).thenReturn(domain);

        // when
        final ActiveProfile result = this.adapter.getActiveProfile();

        // then
        assertThat(result).isSameAs(domain);
        verify(this.clientCallExecutor).execute(any());
        verify(this.mapper).toDomain(clientResponse);
    }

    @Test
    void putMapsRequestCallsExecutorAndMapsResponse() {
        // given
        final UpdateActiveLlmProfileCommand command =
                new UpdateActiveLlmProfileCommand(3, "ollama", "qwen", null);
        final KnowledgeActiveLlmProfileRequest request =
                new KnowledgeActiveLlmProfileRequest(3, "ollama", "qwen", null);
        final KnowledgeActiveLlmProfileResponse clientResponse =
                new KnowledgeActiveLlmProfileResponse(4L, selection());
        final ActiveLlmProfileUpdateResult domain =
                new ActiveLlmProfileUpdateResult(4, new ActiveLlmSelection("ollama", "qwen", null));
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.clientCallExecutor.<KnowledgeActiveLlmProfileResponse>execute(any()))
                .thenReturn(clientResponse);
        when(this.mapper.toDomain(clientResponse)).thenReturn(domain);

        // when
        final ActiveLlmProfileUpdateResult result = this.adapter.updateActiveLlmProfile(command);

        // then
        assertThat(result).isSameAs(domain);
        verify(this.mapper).toRequest(command);
        verify(this.clientCallExecutor).execute(any());
        verify(this.mapper).toDomain(clientResponse);
    }

    private static KnowledgeActiveLlmProfileDetails details() {
        return new KnowledgeActiveLlmProfileDetails("ollama", "qwen", null);
    }

    private static KnowledgeActiveLlmSelection selection() {
        return new KnowledgeActiveLlmSelection("ollama", "qwen", null);
    }
}
