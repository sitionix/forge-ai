package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NodeInputContentPolicyRegistry {

    private final List<NodeInputContentPolicy> policies;

    public NodeExecutionInputContent assemble(final NodeInputContentContext context) {
        return this.policies.stream()
                .filter(policy -> policy.supports(context))
                .findFirst()
                .orElseThrow(() -> new ConflictException("NODE_INPUT_CONTENT_POLICY_NOT_FOUND", "No node input content policy supports this node run."))
                .assemble(context);
    }
}
