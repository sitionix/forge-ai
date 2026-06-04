package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AgentExecutorLaneCompletionContractResolver implements LaneCompletionContractResolver {

    @Override
    public Class<? extends AgentTicketPayload> inputPayloadType(final Agent sourceAgent, final Agent targetAgent) {
        return targetAgent.inputPayloadTypeFrom(sourceAgent)
                .orElseThrow(() -> new IllegalArgumentException("No input payload contract configured for source agent: "
                        + sourceAgent + ", target agent: " + targetAgent));
    }

    @Override
    public boolean writesProducedLaneOutputs(final Agent agent) {
        return agent.writesProducedLaneOutputs();
    }

    @Override
    public boolean requiresApiCompletionEvidence(final Agent agent) {
        return agent.requiresApiCompletionEvidence();
    }

    @Override
    public boolean requiresCompletionOutputForEveryTarget(final Agent agent) {
        return agent.requiresCompletionOutputForEveryTarget();
    }

    @Override
    public Optional<Class<? extends AgentTicketPayload>> completionReportPayloadType(final Agent agent) {
        return agent.completionReportPayloadType();
    }
}
