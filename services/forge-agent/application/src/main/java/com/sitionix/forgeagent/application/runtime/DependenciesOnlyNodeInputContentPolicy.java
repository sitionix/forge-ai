package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodeInputContribution;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
@RequiredArgsConstructor
public class DependenciesOnlyNodeInputContentPolicy implements NodeInputContentPolicy {

    private final WorkflowRunGraphRepository graphRepository;
    private final NodeRunRepository nodeRunRepository;

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

    protected List<NodeInputContribution> contributions(final NodeInputContentContext context) {
        final Map<UUID, NodeRun> sourceRuns = this.nodeRunRepository
                .findByIds(context.consumedContributions().stream()
                        .map(ConnectionResolution::sourceNodeRunId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(NodeRun::id, Function.identity()));
        return context.consumedContributions().stream()
                .sorted(Comparator
                        .comparing(ConnectionResolution::sourceConnectionId)
                        .thenComparing(ConnectionResolution::sourceNodeRunId))
                .map(resolution -> new NodeInputContribution(
                        resolution.sourceNodeRunId(),
                        resolution.sourceConnectionId(),
                        resolution.payload(),
                        Optional.ofNullable(sourceRuns.get(resolution.sourceNodeRunId()))
                                .orElseThrow(() -> new ConflictException("SOURCE_NODE_RUN_NOT_FOUND",
                                        "Source node run for input contribution was not found."))
                                .repositoryId()
                ))
                .toList();
    }

    protected RunPort entryInputPort(final NodeInputContentContext context) {
        return this.graphRepository.findPort(context.workflowRun().id(), context.nodeRun().enteredViaInputPortId())
                .orElseThrow(() -> new ConflictException("RUN_PORT_NOT_FOUND", "Runtime entry input port was not found."));
    }
}
