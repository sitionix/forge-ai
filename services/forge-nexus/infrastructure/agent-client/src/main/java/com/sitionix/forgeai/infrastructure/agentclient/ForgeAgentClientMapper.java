package com.sitionix.forgeai.infrastructure.agentclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDependencySummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDependencyResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;

@Component
public class ForgeAgentClientMapper {

    private final ObjectMapper objectMapper;

    public ForgeAgentClientMapper(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AgentProjectRequest toRequest(final CreateAgentProjectCommand command) {
        return new AgentProjectRequest(command.name());
    }

    AgentDefinitionRequest toRequest(final SaveAgentDefinitionCommand command) {
        try {
            return new AgentDefinitionRequest(
                    command.name(),
                    command.instructions(),
                    this.objectMapper.readTree(command.outputSchema().jsonObject()),
                    command.dependsOnAgentIds() == null ? List.of() : command.dependsOnAgentIds()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Output schema must be valid JSON.", exception);
        }
    }

    AgentProject toDomain(final AgentProjectResponse response) {
        this.requireResponse(response, "project");
        this.requireId(response.id(), "project.id");
        this.requireText(response.name(), "project.name");
        return new AgentProject(response.id(), response.name(), response.createdAt(), response.updatedAt());
    }

    AgentDefinitionListItem toDomain(final AgentDefinitionListResponse response) {
        this.requireResponse(response, "agent list item");
        this.requireId(response.id(), "agent.id");
        this.requireId(response.projectId(), "agent.projectId");
        this.requireText(response.name(), "agent.name");
        return new AgentDefinitionListItem(
                response.id(),
                response.projectId(),
                response.name(),
                this.toDependencies(response.dependsOn()),
                response.createdAt(),
                response.updatedAt()
        );
    }

    AgentDefinitionDetails toDomain(final AgentDefinitionResponse response) {
        this.requireResponse(response, "agent");
        this.requireId(response.id(), "agent.id");
        this.requireId(response.projectId(), "agent.projectId");
        this.requireText(response.name(), "agent.name");
        this.requireText(response.instructions(), "agent.instructions");
        if (response.outputSchema() == null || !response.outputSchema().isObject()) {
            throw this.invalid("agent.outputSchema must be a JSON object");
        }
        try {
            return new AgentDefinitionDetails(
                    response.id(),
                    response.projectId(),
                    response.name(),
                    response.instructions(),
                    new AgentOutputSchemaDocument(this.objectMapper.writeValueAsString(response.outputSchema())),
                    this.toDependencies(response.dependsOn()),
                    response.createdAt(),
                    response.updatedAt()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Forge Agent response output schema was invalid.", exception);
        }
    }

    private List<AgentDependencySummary> toDependencies(final List<AgentDependencyResponse> dependencies) {
        return this.requireList(dependencies, "agent.dependsOn").stream()
                .map(dependency -> {
                    this.requireResponse(dependency, "agent dependency");
                    this.requireId(dependency.id(), "dependency.id");
                    this.requireText(dependency.name(), "dependency.name");
                    return new AgentDependencySummary(dependency.id(), dependency.name());
                })
                .toList();
    }

    <T> List<T> requireList(final List<T> responses, final String field) {
        if (responses == null) {
            throw this.invalid(field + " must not be null");
        }
        return responses;
    }

    private void requireResponse(final Object response, final String label) {
        if (response == null) {
            throw this.invalid(label + " response must not be null");
        }
    }

    private void requireId(final UUID id, final String field) {
        if (id == null) {
            throw this.invalid(field + " must not be null");
        }
    }

    private void requireText(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw this.invalid(field + " must not be blank");
        }
    }

    private HttpMessageConversionException invalid(final String message) {
        return new HttpMessageConversionException("Forge Agent response contract violation: " + message);
    }
}
