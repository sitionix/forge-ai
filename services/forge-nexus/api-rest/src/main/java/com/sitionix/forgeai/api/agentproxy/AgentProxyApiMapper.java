package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDependencySummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentProxyApiMapper {

    private final ObjectMapper objectMapper;

    public CreateAgentProjectCommand toCommand(final AgentProjectRequest request) {
        return new CreateAgentProjectCommand(request.name());
    }

    public SaveAgentDefinitionCommand toCommand(final AgentDefinitionRequest request) {
        if (request.outputSchema() == null || !request.outputSchema().isObject()) {
            throw new IllegalArgumentException("Output schema must be a JSON object.");
        }
        try {
            return new SaveAgentDefinitionCommand(
                    request.name(),
                    request.instructions(),
                    new AgentOutputSchemaDocument(this.objectMapper.writeValueAsString(request.outputSchema())),
                    request.dependsOnAgentIds() == null ? List.of() : request.dependsOnAgentIds()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalArgumentException("Output schema must be valid JSON.", exception);
        }
    }

    public AgentProjectResponse toResponse(final AgentProject project) {
        return new AgentProjectResponse(project.id(), project.name(), project.createdAt(), project.updatedAt());
    }

    public AgentDefinitionListResponse toResponse(final AgentDefinitionListItem agent) {
        return new AgentDefinitionListResponse(
                agent.id(),
                agent.projectId(),
                agent.name(),
                this.toDependencyResponses(agent.dependsOn()),
                agent.createdAt(),
                agent.updatedAt()
        );
    }

    public AgentDefinitionResponse toResponse(final AgentDefinitionDetails agent) {
        try {
            return new AgentDefinitionResponse(
                    agent.id(),
                    agent.projectId(),
                    agent.name(),
                    agent.instructions(),
                    this.objectMapper.readTree(agent.outputSchema().jsonObject()),
                    this.toDependencyResponses(agent.dependsOn()),
                    agent.createdAt(),
                    agent.updatedAt()
            );
        } catch (final JsonProcessingException exception) {
            throw new IllegalStateException("Agent output schema is not valid JSON.", exception);
        }
    }

    private List<AgentDependencyResponse> toDependencyResponses(final List<AgentDependencySummary> dependencies) {
        return dependencies.stream()
                .map(dependency -> new AgentDependencyResponse(dependency.id(), dependency.name()))
                .toList();
    }
}
