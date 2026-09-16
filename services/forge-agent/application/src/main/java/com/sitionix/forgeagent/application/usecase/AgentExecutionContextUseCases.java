package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.AgentExecutionContext;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentExecutionContextUseCases {
    private final AgentExecutionSessionRepository sessions;

    @Transactional(readOnly = true)
    public List<AgentExecutionContext> list(final UUID workflowRunId) {
        return this.sessions.findContextsByWorkflowRunId(workflowRunId);
    }
}
