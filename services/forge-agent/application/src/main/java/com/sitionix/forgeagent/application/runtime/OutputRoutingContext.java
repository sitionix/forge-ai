package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunPort;
import java.util.List;

public record OutputRoutingContext(
        NodeRun nodeRun,
        NodeRunOutput output,
        List<RunPort> availableOutputs,
        List<RunConnection> outgoingConnections
) {
}
