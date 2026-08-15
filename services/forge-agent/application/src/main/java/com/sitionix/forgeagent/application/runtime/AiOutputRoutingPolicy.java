package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
public class AiOutputRoutingPolicy implements OutputRoutingPolicy {

    private final AiOutputRouter router;

    public AiOutputRoutingPolicy(final java.util.Optional<AiOutputRouter> router) {
        this.router = router.orElse(null);
    }

    @Override
    public boolean supports(final OutputRoutingContext context) {
        return context.availableOutputs().size() > 1;
    }

    @Override
    public OutputRoutingDecision route(final OutputRoutingContext context) {
        if (this.router == null) {
            throw new ConflictException("AI_OUTPUT_ROUTER_NOT_CONFIGURED", "AI output routing is not configured.");
        }
        final NodeRunExecutionModel executionModel = this.executionModel(context);
        final UUID selected;
        try {
            selected = this.router.selectOutput(context.output(), context.availableOutputs(), executionModel);
        } catch (final RuntimeException exception) {
            throw new ConflictException("AI_OUTPUT_ROUTING_FAILED", this.failureMessage(exception));
        }
        if (selected == null) {
            throw new ConflictException("AI_OUTPUT_ROUTING_INVALID_PORT", "AI output routing did not select an output port.");
        }
        final Set<UUID> valid = context.availableOutputs().stream()
                .map(com.sitionix.forgeagent.domain.model.RunPort::sourcePortId)
                .collect(Collectors.toSet());
        if (!valid.contains(selected)) {
            throw new ConflictException("AI_OUTPUT_ROUTING_INVALID_PORT", "AI output routing selected an unknown output port.");
        }
        return new SelectedOutputRoutingDecision(selected);
    }

    private NodeRunExecutionModel executionModel(final OutputRoutingContext context) {
        final NodeRunExecutionModel executionModel = context.nodeRun() == null ? null : context.nodeRun().executionModel();
        if (executionModel == null || this.isBlank(executionModel.modelId())) {
            throw new ConflictException("AI_OUTPUT_ROUTING_MODEL_NOT_CONFIGURED", "Snapshotted node run model is not configured for AI output routing.");
        }
        return executionModel;
    }

    private boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    private String failureMessage(final RuntimeException exception) {
        final String message = exception.getMessage();
        return message == null || message.isBlank() ? "AI output routing failed." : message;
    }
}
