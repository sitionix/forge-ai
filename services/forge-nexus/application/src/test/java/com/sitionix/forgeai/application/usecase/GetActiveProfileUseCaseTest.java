package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfile;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
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
class GetActiveProfileUseCaseTest {

    @Mock
    private KnowledgeActiveProfileClient client;

    private GetActiveProfileUseCase useCase;

    @BeforeEach
    void setUp() {
        this.useCase = new GetActiveProfileUseCase(this.client);
    }

    @Test
    void executeDelegatesExactlyOnceToDomainClientPort() {
        // given
        final ActiveProfile profile = new ActiveProfile(1, new ActiveLlmProfile("ollama", "qwen", null), null);
        when(this.client.getActiveProfile()).thenReturn(profile);

        // when
        final ActiveProfile result = this.useCase.execute();

        // then
        assertThat(result).isSameAs(profile);
        verify(this.client).getActiveProfile();
    }
}
