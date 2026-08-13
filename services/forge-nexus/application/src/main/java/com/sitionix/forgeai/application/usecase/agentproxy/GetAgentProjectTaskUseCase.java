package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.GetAgentProjectTask;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetAgentProjectTaskUseCase implements GetAgentProjectTask {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentProjectTask execute(final UUID taskId) {
        return this.forgeAgentClient.getProjectTask(taskId);
    }
}
