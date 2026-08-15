package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunPort;
import java.util.List;

public record NodeRunRoutingContext(
        NodeRun nodeRun,
        List<RunPort> outputs,
        List<RunConnection> outgoing
) {
    OutputRoutingContext outputRoutingContext() {
        return new OutputRoutingContext(this.nodeRun, this.nodeRun.output(), this.outputs, this.outgoing);
    }
}
