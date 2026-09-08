package com.sitionix.forgeagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.AgentExecutionEventPageResponse;
import com.sitionix.forgeagent.application.usecase.AgentExecutionEventUseCases;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCaptureStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventPage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentExecutionEventsControllerTest {
    private static final UUID TURN_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Mock
    private AgentExecutionEventUseCases useCases;

    @Test
    void returnsTypedIncrementalEventPage() {
        final AgentExecutionEventPage page = new AgentExecutionEventPage(
                TURN_ID, AgentExecutionEventCaptureStatus.UNAVAILABLE, List.of(), 5, 5, false);
        when(this.useCases.page(TURN_ID, 5, 100)).thenReturn(page);
        final ForgeAgentApiMapper mapper = new ForgeAgentApiMapper(new ObjectMapper());

        final AgentExecutionEventPageResponse response = new AgentExecutionEventsController(this.useCases, mapper)
                .page(TURN_ID, 5, 100);

        assertThat(response.turnId()).isEqualTo(TURN_ID);
        assertThat(response.captureStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.events()).isEmpty();
        assertThat(response.nextAfterSequence()).isEqualTo(5);
        verify(this.useCases).page(TURN_ID, 5, 100);
    }
}
