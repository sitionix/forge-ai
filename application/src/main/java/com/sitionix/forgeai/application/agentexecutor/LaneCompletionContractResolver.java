package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import java.util.Optional;

public interface LaneCompletionContractResolver {

    Class<? extends AgentTicketPayload> inputPayloadType(Agent sourceAgent, Agent targetAgent);

    boolean writesProducedLaneOutputs(Agent agent);

    boolean requiresApiCompletionEvidence(Agent agent);

    boolean requiresCompletionOutputForEveryTarget(Agent agent);

    Optional<Class<? extends AgentTicketPayload>> completionReportPayloadType(Agent agent);
}
