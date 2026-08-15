package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.RunPort;
import java.util.List;
import java.util.UUID;

public interface AiOutputRouter {

    UUID selectOutput(NodeRunOutput output, List<RunPort> availableOutputs, NodeRunExecutionModel executionModel);
}
