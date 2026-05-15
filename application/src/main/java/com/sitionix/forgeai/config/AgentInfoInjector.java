package com.sitionix.forgeai.config;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.port.AgentPropertiesProvider;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentInfoInjector {

    private final AgentPropertiesProvider agentPropertiesProvider;

    @PostConstruct
    public void injectInfo() {
        if (this.agentPropertiesProvider.getAgents() == null) {
            throw new IllegalStateException("No agents configured in agent.yml");
        }

        final Map<String, AgentPropertiesProvider.AgentConfigView> infoById = this.agentPropertiesProvider.getAgents()
                .stream()
                .collect(Collectors.toMap(AgentPropertiesProvider.AgentConfigView::getId, Function.identity()));

        for (final Agent agent : Agent.values()) {
            final AgentPropertiesProvider.AgentConfigView config = infoById.get(agent.getId());
            if (config == null) {
                throw new IllegalStateException("No agent config found for id: " + agent.getId());
            }
            agent.setInfo(config);
        }
    }
}
