package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.CreateAgentProjectTask;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreateAgentProjectTaskUseCase implements CreateAgentProjectTask {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentProjectTask execute(final UUID projectId, final CreateAgentProjectTaskCommand command) {
        return this.forgeAgentClient.createProjectTask(projectId, command);
    }
}
