package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
@RequiredArgsConstructor
public class RootNodeInputContentPolicy implements NodeInputContentPolicy {

    private final WorkflowRunGraphRepository graphRepository;

    @Override
    public boolean supports(final NodeInputContentContext context) {
        return context.nodeRun().activationFrameId() == null;
    }

    @Override
    public NodeExecutionInputContent assemble(final NodeInputContentContext context) {
        if (context.nodeRun().enteredViaInputPortId() == null) {
            return new NodeExecutionInputContent(new NodeInputEnvelope(
                    context.workflowRun().input(),
                    null,
                    java.util.List.of()
            ));
        }
        return new NodeExecutionInputContent(new NodeInputEnvelope(
                context.workflowRun().input(),
                this.graphRepository.findPort(context.workflowRun().id(), context.nodeRun().enteredViaInputPortId())
                        .orElseThrow(),
                java.util.List.of()
        ));
    }
}
