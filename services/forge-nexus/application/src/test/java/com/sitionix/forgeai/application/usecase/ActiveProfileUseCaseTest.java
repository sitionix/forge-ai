package com.sitionix.forgeai.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfile;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.domain.port.KnowledgeActiveProfileClient;
import org.junit.jupiter.api.Test;

class ActiveProfileUseCaseTest {

    private final KnowledgeActiveProfileClient client = mock(KnowledgeActiveProfileClient.class);

    @Test
    void getActiveProfileDelegatesToDomainClientPort() {
        final ActiveProfile profile = new ActiveProfile(1, new ActiveLlmProfile("ollama", "qwen", null), null);
        when(this.client.getActiveProfile()).thenReturn(profile);

        final ActiveProfile result = new GetActiveProfileUseCase(this.client).execute();

        assertThat(result).isSameAs(profile);
        verify(this.client).getActiveProfile();
    }

    @Test
    void updateActiveLlmProfileDelegatesToDomainClientPort() {
        final UpdateActiveLlmProfileCommand command = new UpdateActiveLlmProfileCommand(1, "ollama", "qwen", null);
        final ActiveLlmProfileUpdateResult update = new ActiveLlmProfileUpdateResult(
                2,
                new ActiveLlmProfile("ollama", "qwen", null)
        );
        when(this.client.updateActiveLlmProfile(command)).thenReturn(update);

        final ActiveLlmProfileUpdateResult result = new UpdateActiveLlmProfileUseCase(this.client).execute(command);

        assertThat(result).isSameAs(update);
        verify(this.client).updateActiveLlmProfile(command);
    }
}
