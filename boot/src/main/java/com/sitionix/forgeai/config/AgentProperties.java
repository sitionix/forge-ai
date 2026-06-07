package com.sitionix.forgeai.config;

import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.agentticket.AgentTicketPayloadType;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "")
public class AgentProperties implements AgentPropertiesProvider {

    private List<AgentConfig> agents;

    @Override
    public List<AgentConfigView> getAgents() {
        return this.agents == null ? null : List.copyOf(this.agents);
    }

    @Getter
    @Setter
    public static class AgentConfig implements AgentConfigView {
        private String id;
        private String scopeMode;
        private Boolean enabled;
        private List<String> groups;
        private List<String> dependsOn;
        private List<String> produces;
        private String workspaceContractRef;
        private Map<String, String> inputPayloads = new LinkedHashMap<>();
        private CompletionConfig completion = new CompletionConfig();

        @Override
        public ScopeMode getScopeMode() {
            if (this.scopeMode == null) {
                return null;
            }
            return ScopeMode.byId(this.scopeMode);
        }

        @Override
        public List<Agent> getDependsOn() {
            if (this.dependsOn == null) {
                return List.of();
            }
            return this.dependsOn.stream()
                    .map(Agent::byId)
                    .toList();
        }

        @Override
        public List<Agent> getProduces() {
            if (this.produces == null) {
                return List.of();
            }
            return this.produces.stream()
                    .map(Agent::byId)
                    .toList();
        }

        @Override
        public Set<ServiceGroup> getGroups() {
            if (this.groups == null) {
                return Set.of();
            }
            return this.groups.stream()
                    .map(value -> ServiceGroup.valueOf(value.toUpperCase(Locale.ROOT)))
                    .collect(Collectors.toSet());
        }

        @Override
        public boolean isEnabled() {
            return this.enabled == null || this.enabled;
        }

        @Override
        public Map<Agent, AgentTicketPayloadType> getInputPayloadTypes() {
            if (this.inputPayloads == null) {
                return Map.of();
            }
            return this.inputPayloads.entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> Agent.byId(entry.getKey()),
                            entry -> AgentTicketPayloadType.byId(entry.getValue()),
                            (first, second) -> second,
                            LinkedHashMap::new
                    ));
        }

        @Override
        public boolean writesProducedLaneOutputs() {
            return this.completion == null
                    || this.completion.getWritesProducedLaneOutputs() == null
                    || this.completion.getWritesProducedLaneOutputs();
        }

        @Override
        public boolean requiresApiCompletionEvidence() {
            return this.completion != null
                    && Boolean.TRUE.equals(this.completion.getRequiresApiEvidence());
        }

        @Override
        public boolean requiresCompletionOutputForEveryTarget() {
            return this.completion == null
                    || this.completion.getRequiresOutputForEveryTarget() == null
                    || this.completion.getRequiresOutputForEveryTarget();
        }

        @Override
        public Optional<AgentTicketPayloadType> getCompletionReportPayloadType() {
            if (this.completion == null || this.completion.getReportPayload() == null) {
                return Optional.empty();
            }
            return Optional.of(AgentTicketPayloadType.byId(this.completion.getReportPayload()));
        }

        @Override
        public Optional<String> getWorkspaceContractRef() {
            if (this.workspaceContractRef == null || this.workspaceContractRef.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(this.workspaceContractRef);
        }
    }

    @Getter
    @Setter
    public static class CompletionConfig {
        private Boolean writesProducedLaneOutputs;
        private Boolean requiresApiEvidence;
        private Boolean requiresOutputForEveryTarget;
        private String reportPayload;
    }
}
