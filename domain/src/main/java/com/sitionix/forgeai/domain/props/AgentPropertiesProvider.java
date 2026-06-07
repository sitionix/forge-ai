package com.sitionix.forgeai.domain.props;

import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.agentticket.AgentTicketPayloadType;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Facade port for YAML agent properties.
 */
public interface AgentPropertiesProvider {

    List<AgentConfigView> getAgents();

    interface AgentConfigView {
        String getId();

        ScopeMode getScopeMode();

        Set<ServiceGroup> getGroups();

        List<Agent> getDependsOn();

        List<Agent> getProduces();

        boolean isEnabled();

        default Map<Agent, AgentTicketPayloadType> getInputPayloadTypes() {
            return Map.of();
        }

        default boolean writesProducedLaneOutputs() {
            return true;
        }

        default boolean requiresApiCompletionEvidence() {
            return false;
        }

        default boolean requiresCompletionOutputForEveryTarget() {
            return true;
        }

        default Optional<AgentTicketPayloadType> getCompletionReportPayloadType() {
            return Optional.empty();
        }

        default Optional<String> getWorkspaceContractRef() {
            return Optional.empty();
        }
    }
}
