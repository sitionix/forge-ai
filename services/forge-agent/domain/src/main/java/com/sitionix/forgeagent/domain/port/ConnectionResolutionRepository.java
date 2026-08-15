package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ConnectionResolutionRepository {

    List<ConnectionResolution> findByWorkflowRunAndFrame(UUID workflowRunId, UUID executionFrameId);

    List<ConnectionResolution> findBySourceNodeRunId(UUID sourceNodeRunId);

    List<ConnectionResolution> findConsumedByNodeRunId(UUID nodeRunId);

    void saveAll(Collection<ConnectionResolution> resolutions);

    int markConsumed(Collection<UUID> resolutionIds, UUID nodeRunId);
}
