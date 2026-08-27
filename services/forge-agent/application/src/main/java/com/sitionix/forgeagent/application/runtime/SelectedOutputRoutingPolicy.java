package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
public class SelectedOutputRoutingPolicy implements OutputRoutingPolicy {

    public static final String INVALID_SELECTED_OUTPUT_PORT = "INVALID_SELECTED_OUTPUT_PORT";

    @Override
    public boolean supports(final OutputRoutingContext context) {
        return context.availableOutputs().size() > 1;
    }

    @Override
    public OutputRoutingDecision route(final OutputRoutingContext context) {
        final UUID selected = context.nodeRun() == null ? null : context.nodeRun().selectedOutputPortId();
        if (selected == null) {
            throw new ConflictException(INVALID_SELECTED_OUTPUT_PORT, "Agent execution did not select an output port.");
        }
        final Set<UUID> valid = context.availableOutputs().stream()
                .map(com.sitionix.forgeagent.domain.model.RunPort::sourcePortId)
                .collect(Collectors.toSet());
        if (!valid.contains(selected)) {
            throw new ConflictException(INVALID_SELECTED_OUTPUT_PORT, "Agent execution selected an unknown output port.");
        }
        return new SelectedOutputRoutingDecision(selected);
    }
}
