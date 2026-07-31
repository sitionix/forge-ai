package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfile;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileDetails;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileRequest;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import java.util.function.Supplier;
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
        final KnowledgeActiveProfileResponse clientResponse =
                new KnowledgeActiveProfileResponse(1L, details(), null);
        final ActiveProfile domain =
                new ActiveProfile(1, new ActiveLlmProfile("ollama", "qwen", null), null);
        this.executeSupplier();
        when(this.httpClient.getActiveProfile()).thenReturn(clientResponse);
        when(this.mapper.toDomain(clientResponse)).thenReturn(domain);

        final ActiveProfile result = this.adapter.getActiveProfile();

        assertThat(result).isSameAs(domain);
        verify(this.clientCallExecutor).execute(any());
        verify(this.httpClient).getActiveProfile();
        verify(this.mapper).toDomain(clientResponse);
    }

    @Test
    void putMapsRequestCallsExecutorAndMapsResponse() {
        final UpdateActiveLlmProfileCommand command =
                new UpdateActiveLlmProfileCommand(3, "ollama", "qwen", null);
        final KnowledgeActiveLlmProfileRequest request =
                new KnowledgeActiveLlmProfileRequest(3, "ollama", "qwen", null);
        final KnowledgeActiveLlmProfileResponse clientResponse =
                new KnowledgeActiveLlmProfileResponse(4L, details());
        final ActiveLlmProfileUpdateResult domain =
                new ActiveLlmProfileUpdateResult(4, new ActiveLlmProfile("ollama", "qwen", null));
        this.executeSupplier();
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.updateActiveLlmProfile(request)).thenReturn(clientResponse);
        when(this.mapper.toDomain(clientResponse)).thenReturn(domain);

        final ActiveLlmProfileUpdateResult result = this.adapter.updateActiveLlmProfile(command);

        assertThat(result).isSameAs(domain);
        verify(this.mapper).toRequest(command);
        verify(this.clientCallExecutor).execute(any());
        verify(this.httpClient).updateActiveLlmProfile(request);
        verify(this.mapper).toDomain(clientResponse);
    }

    @SuppressWarnings("unchecked")
    private void executeSupplier() {
        when(this.clientCallExecutor.execute(any()))
                .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(0)).get());
    }

    private static KnowledgeActiveLlmProfileDetails details() {
        return new KnowledgeActiveLlmProfileDetails("ollama", "qwen", null);
    }
}
