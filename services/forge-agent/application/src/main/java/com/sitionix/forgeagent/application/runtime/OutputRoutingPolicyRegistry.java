package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutputRoutingPolicyRegistry {

    private final List<OutputRoutingPolicy> policies;

    public OutputRoutingDecision route(final OutputRoutingContext context) {
        return this.policies.stream()
                .filter(policy -> policy.supports(context))
                .findFirst()
                .orElseThrow(() -> new ConflictException("OUTPUT_ROUTING_POLICY_NOT_FOUND", "No output routing policy supports this node run."))
                .route(context);
    }
}
