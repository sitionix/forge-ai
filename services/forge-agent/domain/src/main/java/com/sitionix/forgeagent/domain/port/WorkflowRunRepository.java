package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRunRepository {

    WorkflowRun save(WorkflowRun run);

    Optional<WorkflowRun> findById(UUID runId);

    Optional<WorkflowRun> findByIdForUpdate(UUID runId);

    Optional<WorkflowRun> findLatestByTaskId(UUID taskId);

    List<WorkflowRunSummary> findSummariesBySourceWorkflowId(UUID workflowId);

    List<WorkflowRunSummary> findSummariesByTaskId(UUID taskId);

    WorkflowRun saveLifecycle(WorkflowRun run);

    boolean existsActiveByProjectId(UUID projectId);

    boolean existsActiveByTaskId(UUID taskId);

    boolean existsActiveBySourceWorkflowId(UUID workflowId);
}
