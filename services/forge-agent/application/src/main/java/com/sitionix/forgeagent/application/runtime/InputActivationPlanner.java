package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import java.util.Collection;
import java.util.UUID;

public interface InputActivationPlanner {

    void planFromResolutions(WorkflowRun workflowRun, Collection<ConnectionResolution> resolutions);
}
