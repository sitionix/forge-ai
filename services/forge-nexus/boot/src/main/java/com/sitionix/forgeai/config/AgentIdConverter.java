package com.sitionix.forgeai.config;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class AgentIdConverter implements Converter<String, Agent> {

    @Override
    public Agent convert(final String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        return Agent.byId(source.trim());
    }
}
