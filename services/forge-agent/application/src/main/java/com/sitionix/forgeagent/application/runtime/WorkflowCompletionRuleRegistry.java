package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkflowCompletionRuleRegistry {

    private final List<WorkflowCompletionRule> rules;

    public WorkflowCompletionDecision evaluate(final WorkflowCompletionContext context) {
        return this.rules.stream()
                .filter(rule -> rule.supports(context))
                .findFirst()
                .orElseThrow(() -> new ConflictException("WORKFLOW_COMPLETION_RULE_NOT_FOUND", "No workflow completion rule supports this run."))
                .decision(context);
    }
}
