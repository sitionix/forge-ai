package com.sitionix.forgeagent.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.AgentDependencyResponse;
import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.application.usecase.CreateProjectCommand;
import com.sitionix.forgeagent.application.usecase.SaveAgentCommand;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDependencySummary;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentListItem;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Project;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ForgeAgentApiMapper {

    private final ObjectMapper objectMapper;

    CreateProjectCommand toCommand(final CreateProjectRequest request) {
        return new CreateProjectCommand(request.name());
    }

    SaveAgentCommand toCommand(final SaveAgentRequest request) {
        final JsonNode outputSchema = request.outputSchema();
        if (outputSchema == null || !outputSchema.isObject()) {
            throw new ValidationException("INVALID_OUTPUT_SCHEMA", "Output schema must be a JSON object.");
        }
        try {
            return new SaveAgentCommand(
                    request.name(),
                    request.instructions(),
                    AgentOutputSchema.ofCanonicalJsonObject(this.objectMapper.writeValueAsString(outputSchema)),
                    request.dependsOnAgentIds() == null ? List.of() : request.dependsOnAgentIds()
            );
        } catch (final JsonProcessingException exception) {
            throw new ValidationException("INVALID_OUTPUT_SCHEMA", "Output schema must be valid JSON.");
        }
    }

    ProjectResponse toResponse(final Project project) {
        return new ProjectResponse(project.id(), project.name(), project.createdAt(), project.updatedAt());
    }

    AgentListResponse toResponse(final AgentListItem agent) {
        return new AgentListResponse(
                agent.id(),
                agent.projectId(),
                agent.name(),
                this.toDependencyResponses(agent.dependsOn()),
                agent.createdAt(),
                agent.updatedAt()
        );
    }

    AgentResponse toResponse(final AgentDetails agent) {
        try {
            return new AgentResponse(
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
            throw new IllegalStateException("Stored output schema is invalid JSON", exception);
        }
    }

    private List<AgentDependencyResponse> toDependencyResponses(final List<AgentDependencySummary> dependencies) {
        return dependencies.stream()
                .map(dependency -> new AgentDependencyResponse(dependency.id(), dependency.name()))
                .toList();
    }
}
