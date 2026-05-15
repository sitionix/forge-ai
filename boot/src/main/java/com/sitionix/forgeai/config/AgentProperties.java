package com.sitionix.forgeai.config;

import com.sitionix.forgeai.domain.model.service.ServiceGroup;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.port.AgentPropertiesProvider;
import java.util.List;
import java.util.Locale;
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
        private List<String> groups;
        private List<String> dependsOn;
        private List<String> produces;

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
    }
}
