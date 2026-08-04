package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ListAgentProjects;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ListAgentProjectsUseCase implements ListAgentProjects {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public List<AgentProject> execute() {
        return this.forgeAgentClient.listProjects();
    }
}
