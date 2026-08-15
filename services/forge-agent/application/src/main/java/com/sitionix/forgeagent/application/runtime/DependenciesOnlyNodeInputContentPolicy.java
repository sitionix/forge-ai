package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
@RequiredArgsConstructor
public class DependenciesOnlyNodeInputContentPolicy implements NodeInputContentPolicy {

    private final NodeRunRepository nodeRunRepository;

    @Override
    public boolean supports(final NodeInputContentContext context) {
        return context.nodeRun().inputMode() == NodeInputMode.DEPENDENCIES_ONLY;
    }

    @Override
    public NodeExecutionInputContent assemble(final NodeInputContentContext context) {
        return new NodeExecutionInputContent("", this.dependencies(context));
    }

    protected java.util.List<NodeDependencyOutput> dependencies(final NodeInputContentContext context) {
        return context.consumedContributions().stream()
                .sorted(Comparator.comparing(com.sitionix.forgeagent.domain.model.ConnectionResolution::sourceConnectionId))
                .map(resolution -> {
                    final com.sitionix.forgeagent.domain.model.NodeRun source = this.nodeRunRepository.findById(resolution.sourceNodeRunId()).orElseThrow();
                    return new NodeDependencyOutput(resolution.sourceNodeRunId(), source.agentName(), resolution.payload());
                })
                .toList();
    }
}
