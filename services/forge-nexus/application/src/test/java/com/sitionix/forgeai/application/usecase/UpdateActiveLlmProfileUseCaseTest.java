package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmSelection;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.domain.port.KnowledgeActiveProfileClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateActiveLlmProfileUseCaseTest {

    @Mock
    private KnowledgeActiveProfileClient client;

    private UpdateActiveLlmProfileUseCase useCase;

    @BeforeEach
    void setUp() {
        this.useCase = new UpdateActiveLlmProfileUseCase(this.client);
    }

    @Test
    void executeDelegatesExactlyOnceToDomainClientPort() {
        // given
        final UpdateActiveLlmProfileCommand command = new UpdateActiveLlmProfileCommand(1, "ollama", "qwen", null);
        final ActiveLlmProfileUpdateResult update = new ActiveLlmProfileUpdateResult(
                2,
                new ActiveLlmSelection("ollama", "qwen", null)
        );
        when(this.client.updateActiveLlmProfile(command)).thenReturn(update);

        // when
        final ActiveLlmProfileUpdateResult result = this.useCase.execute(command);

        // then
        assertThat(result).isSameAs(update);
        verify(this.client).updateActiveLlmProfile(command);
    }
}
