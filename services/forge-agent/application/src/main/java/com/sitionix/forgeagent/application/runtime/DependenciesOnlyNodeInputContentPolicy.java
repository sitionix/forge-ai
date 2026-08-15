package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodeInputContribution;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
@RequiredArgsConstructor
public class DependenciesOnlyNodeInputContentPolicy implements NodeInputContentPolicy {

    private final NodeRunRepository nodeRunRepository;
    private final WorkflowRunGraphRepository graphRepository;

    @Override
    public boolean supports(final NodeInputContentContext context) {
        return context.nodeRun().inputMode() == NodeInputMode.DEPENDENCIES_ONLY;
    }

    @Override
    public NodeExecutionInputContent assemble(final NodeInputContentContext context) {
        return new NodeExecutionInputContent(new NodeInputEnvelope(
                null,
                this.entryInputPort(context),
                this.contributions(context)
        ));
    }

    protected java.util.List<NodeInputContribution> contributions(final NodeInputContentContext context) {
        return context.consumedContributions().stream()
                .sorted(Comparator
                        .comparing(com.sitionix.forgeagent.domain.model.ConnectionResolution::sourceNodeRunId)
                        .thenComparing(com.sitionix.forgeagent.domain.model.ConnectionResolution::sourceConnectionId))
                .peek(resolution -> this.nodeRunRepository.findById(resolution.sourceNodeRunId()).orElseThrow())
                .map(resolution -> new NodeInputContribution(
                        resolution.sourceNodeRunId(),
                        resolution.sourceConnectionId(),
                        resolution.payload()
                ))
                .toList();
    }

    protected RunPort entryInputPort(final NodeInputContentContext context) {
        return this.graphRepository.findPort(context.workflowRun().id(), context.nodeRun().enteredViaInputPortId())
                .orElseThrow();
    }
}
