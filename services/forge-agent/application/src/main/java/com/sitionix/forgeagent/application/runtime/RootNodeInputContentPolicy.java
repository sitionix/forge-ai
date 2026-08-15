package com.sitionix.forgeagent.application.runtime;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class RootNodeInputContentPolicy implements NodeInputContentPolicy {

    @Override
    public boolean supports(final NodeInputContentContext context) {
        return context.nodeRun().enteredViaInputPortId() == null;
    }

    @Override
    public NodeExecutionInputContent assemble(final NodeInputContentContext context) {
        return new NodeExecutionInputContent(new com.sitionix.forgeagent.domain.model.NodeInputEnvelope(
                context.workflowRun().input(),
                null,
                java.util.List.of()
        ));
    }
}
