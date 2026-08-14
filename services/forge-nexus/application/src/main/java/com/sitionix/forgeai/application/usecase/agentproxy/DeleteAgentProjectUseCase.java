package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.DeleteAgentProject;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeleteAgentProjectUseCase implements DeleteAgentProject {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public void execute(final UUID projectId) {
        this.forgeAgentClient.deleteProject(projectId);
    }
}
