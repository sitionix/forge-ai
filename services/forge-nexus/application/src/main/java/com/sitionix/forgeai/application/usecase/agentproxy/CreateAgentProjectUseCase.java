package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.CreateAgentProject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreateAgentProjectUseCase implements CreateAgentProject {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public AgentProject execute(final CreateAgentProjectCommand command) {
        return this.forgeAgentClient.createProject(command);
    }
}
