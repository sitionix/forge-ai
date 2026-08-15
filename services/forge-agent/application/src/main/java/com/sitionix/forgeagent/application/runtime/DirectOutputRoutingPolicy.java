package com.sitionix.forgeagent.application.runtime;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class DirectOutputRoutingPolicy implements OutputRoutingPolicy {

    @Override
    public boolean supports(final OutputRoutingContext context) {
        final Set<java.util.UUID> connectedOutputs = context.outgoingConnections().stream()
                .map(com.sitionix.forgeagent.domain.model.RunConnection::sourceOutputPortId)
                .collect(Collectors.toSet());
        return connectedOutputs.size() == 1;
    }

    @Override
    public OutputRoutingDecision route(final OutputRoutingContext context) {
        return new SelectedOutputRoutingDecision(context.outgoingConnections().get(0).sourceOutputPortId());
    }
}
