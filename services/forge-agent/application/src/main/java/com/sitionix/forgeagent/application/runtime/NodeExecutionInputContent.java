package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;

public record NodeExecutionInputContent(
        NodeInputEnvelope envelope
) {
}
