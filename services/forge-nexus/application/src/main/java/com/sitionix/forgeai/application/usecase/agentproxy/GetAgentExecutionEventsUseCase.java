package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentExecutionEventPage;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.GetAgentExecutionEvents;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetAgentExecutionEventsUseCase implements GetAgentExecutionEvents {
    private final ForgeAgentClient client;

    @Override
    public AgentExecutionEventPage execute(final UUID turnId, final long afterSequence, final int limit) {
        return this.client.getAgentExecutionEvents(turnId, afterSequence, limit);
    }
}
