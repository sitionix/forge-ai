package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.WorkflowRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRunRepository {

    WorkflowRun save(WorkflowRun run);

    Optional<WorkflowRun> findById(UUID runId);

    List<WorkflowRun> findBySourceWorkflowId(UUID workflowId);
}
