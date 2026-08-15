package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunGraphRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
public class TaskAndDependenciesNodeInputContentPolicy extends DependenciesOnlyNodeInputContentPolicy {

    public TaskAndDependenciesNodeInputContentPolicy(final NodeRunRepository nodeRunRepository,
                                                    final WorkflowRunGraphRepository graphRepository) {
        super(nodeRunRepository, graphRepository);
    }

    @Override
    public boolean supports(final NodeInputContentContext context) {
        return context.nodeRun().inputMode() == NodeInputMode.TASK_AND_DEPENDENCIES;
    }

    @Override
    public NodeExecutionInputContent assemble(final NodeInputContentContext context) {
        return new NodeExecutionInputContent(new NodeInputEnvelope(
                context.workflowRun().input(),
                this.entryInputPort(context),
                this.contributions(context)
        ));
    }
}
