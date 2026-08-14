package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.DeleteAgentProjectTask;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeleteAgentProjectTaskUseCase implements DeleteAgentProjectTask {

    private final ForgeAgentClient forgeAgentClient;

    @Override
    public void execute(final UUID taskId) {
        this.forgeAgentClient.deleteProjectTask(taskId);
    }
}
