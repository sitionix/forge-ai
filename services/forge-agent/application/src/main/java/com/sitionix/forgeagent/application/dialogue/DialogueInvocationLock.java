package com.sitionix.forgeagent.application.dialogue;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DialogueInvocationLock {
    private final WorkflowRunRepository workflows;
    private final NodeRunRepository nodes;

    public Target lock(final UUID runId, final UUID nodeRunId) {
        final WorkflowRun run = workflows.findByIdForUpdate(runId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_RUN_NOT_FOUND", "Workflow run was not found."));
        final NodeRun node = nodes.findByIdForUpdate(nodeRunId).filter(n -> runId.equals(n.workflowRunId()))
                .orElseThrow(() -> new NotFoundException("NODE_RUN_NOT_FOUND", "Node run was not found in the workflow run."));
        requireDialogue(node);
        return new Target(run,node);
    }

    public static void requireDialogue(final NodeRun node) {
        if (node.nodeType() != NodeType.DIALOGUE) {
            throw new ConflictException("DIALOGUE_NOT_ALLOWED", "This node does not support dialogue.");
        }
    }

    public static void requireActive(final Target target) {
        if (target.run().status() != WorkflowRunStatus.QUEUED && target.run().status() != WorkflowRunStatus.RUNNING) {
            throw new ConflictException("WORKFLOW_RUN_NOT_ACTIVE", "The workflow run is no longer active.");
        }
        if (!target.node().status().active()) {
            throw new ConflictException("DIALOGUE_CLOSED", "The dialogue node is no longer active.");
        }
    }

    public record Target(WorkflowRun run, NodeRun node) { }
}
