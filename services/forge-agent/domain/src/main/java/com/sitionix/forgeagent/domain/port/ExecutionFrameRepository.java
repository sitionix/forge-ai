package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionFrameRepository {

    ExecutionFrame save(ExecutionFrame frame);

    Optional<ExecutionFrame> findById(UUID id);

    Optional<ExecutionFrame> findByIdForUpdate(UUID id);

    List<ExecutionFrame> findByWorkflowRunId(UUID workflowRunId);
}
