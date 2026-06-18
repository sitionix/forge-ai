package com.sitionix.forgeai.it.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.AgentTicketPayloadType;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.AgentTicketJpaRepository;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;

public final class AgentTicketJsonFixture {

    private static final String CUSTOM_FIXTURE_PATH = "forge-it/db/mongodb/entities/custom/";
    private static final String DEFAULT_FIXTURE_PATH = "forge-it/db/mongodb/entities/default/";

    private AgentTicketJsonFixture() {
    }

    public static AgentTicketDocument insert(final String fixture,
                                             final ObjectMapper objectMapper,
                                             final AgentTicketJpaRepository repository) {
        try {
            final JsonNode root = objectMapper.readTree(resource(fixture).getInputStream());
            final Agent agent = Agent.valueOf(requiredText(root, "agent"));
            final AgentTicketDocument document = new AgentTicketDocument();
            document.setId(UUID.fromString(requiredText(root, "id")));
            document.setTicketId(UUID.fromString(requiredText(root, "ticketId")));
            document.setSourceLaneId(optionalUuid(root, "sourceLaneId"));
            document.setLaneId(UUID.fromString(requiredText(root, "laneId")));
            document.setStatus(AgentTicketStatus.valueOf(requiredText(root, "status")));
            document.setScope(requiredText(root, "scope"));
            document.setAgent(agent);
            document.setPayload(payload(root, agent, objectMapper));
            document.setCreatedAt(LocalDateTime.parse(requiredText(root, "createdAt")));
            document.setUpdatedAt(LocalDateTime.parse(requiredText(root, "updatedAt")));
            return repository.save(document);
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to read agent ticket fixture: " + fixture, e);
        }
    }

    private static AgentTicketPayload payload(final JsonNode root,
                                              final Agent agent,
                                              final ObjectMapper objectMapper) throws IOException {
        final JsonNode payload = root.get("payload");
        if (payload == null || payload.isNull()) {
            return null;
        }
        return objectMapper.treeToValue(payload, AgentTicketPayloadType.valueOf(agent.name()).getPayloadClass());
    }

    private static UUID optionalUuid(final JsonNode root, final String field) {
        final JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return UUID.fromString(value.asText());
    }

    private static String requiredText(final JsonNode root, final String field) {
        final JsonNode value = root.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required agent ticket fixture field: " + field);
        }
        return value.asText();
    }

    private static ClassPathResource resource(final String fixture) {
        final ClassPathResource custom = new ClassPathResource(CUSTOM_FIXTURE_PATH + fixture);
        if (custom.exists()) {
            return custom;
        }
        return new ClassPathResource(DEFAULT_FIXTURE_PATH + fixture);
    }
}
