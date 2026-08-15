package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
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
        return !context.availableOutputs().isEmpty();
    }

    @Override
    public OutputRoutingDecision route(final OutputRoutingContext context) {
        if (this.router == null) {
            throw new ConflictException("AI_OUTPUT_ROUTER_NOT_CONFIGURED", "AI output routing is not configured.");
        }
        final UUID selected = this.router.selectOutput(context.output(), context.availableOutputs());
        final Set<UUID> valid = context.availableOutputs().stream()
                .map(com.sitionix.forgeagent.domain.model.RunPort::sourcePortId)
                .collect(Collectors.toSet());
        if (!valid.contains(selected)) {
            throw new ConflictException("AI_OUTPUT_ROUTING_INVALID_PORT", "AI output routing selected an unknown output port.");
        }
        return new SelectedOutputRoutingDecision(selected);
    }
}
