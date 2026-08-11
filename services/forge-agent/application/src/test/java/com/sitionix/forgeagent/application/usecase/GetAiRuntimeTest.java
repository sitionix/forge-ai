package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.CodexRuntimeProvider;
import com.sitionix.forgeagent.domain.model.RuntimeProviderStatus;
import com.sitionix.forgeagent.domain.port.CodexRuntimePort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetAiRuntimeTest {

    @Mock
    private CodexRuntimePort codexRuntimePort;

    @Test
    void delegatesToCodexRuntimePort() {
        final CodexRuntimeProvider provider = new CodexRuntimeProvider("codex", "Codex", RuntimeProviderStatus.READY, "codex/1", List.of());
        when(this.codexRuntimePort.getModels()).thenReturn(provider);

        final var catalog = new GetAiRuntime(this.codexRuntimePort).execute();

        assertThat(catalog.providers()).containsExactly(provider);
        verify(this.codexRuntimePort).getModels();
    }
}
