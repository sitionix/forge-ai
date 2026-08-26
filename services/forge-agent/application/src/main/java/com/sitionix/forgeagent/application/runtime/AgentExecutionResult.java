package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import java.util.UUID;

public record AgentExecutionResult(NodeRunOutput output, UUID selectedOutputPortId) {
}
