package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentListItem;
import com.sitionix.forgeagent.domain.model.NameNormalizer;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentUseCases {

    private static final int MAX_NAME_LENGTH = 120;

    private final ProjectRepository projectRepository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<AgentListItem> listProjectAgents(final UUID projectId) {
        this.requireProject(projectId);
        return this.toListItems(this.agentDefinitionRepository.findByProjectId(projectId));
    }

    @Transactional(readOnly = true)
    public AgentDetails getAgent(final UUID agentId) {
        final AgentDefinition agent = this.agentDefinitionRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundException("AGENT_NOT_FOUND", "Agent was not found."));
        return this.toDetails(agent);
    }

    @Transactional
    public AgentDetails createAgent(final UUID projectId, final SaveAgentCommand command) {
        this.requireProject(projectId);
        final String name = this.requireName(command.name());
        final String normalizedName = NameNormalizer.normalize(name);
        if (this.agentDefinitionRepository.existsByProjectIdAndNormalizedName(projectId, normalizedName)) {
            throw new ConflictException("DUPLICATE_AGENT_NAME", "An agent with this name already exists in this project.");
        }
        final String instructions = this.requireInstructions(command.instructions());
        final Instant now = Instant.now(this.clock);
        final AgentDefinition newAgent = new AgentDefinition(
                UUID.randomUUID(),
                projectId,
                name,
                normalizedName,
                instructions,
                command.outputSchema(),
                now,
                now
        );
        final AgentDefinition saved = this.agentDefinitionRepository.save(newAgent);
        return this.toDetails(saved);
    }

    @Transactional
    public AgentDetails updateAgent(final UUID agentId, final SaveAgentCommand command) {
        final AgentDefinition existing = this.agentDefinitionRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundException("AGENT_NOT_FOUND", "Agent was not found."));
        final String name = this.requireName(command.name());
        final String normalizedName = NameNormalizer.normalize(name);
        if (this.agentDefinitionRepository.existsByProjectIdAndNormalizedNameExcludingId(existing.projectId(), normalizedName, existing.id())) {
            throw new ConflictException("DUPLICATE_AGENT_NAME", "An agent with this name already exists in this project.");
        }
        final AgentDefinition updated = new AgentDefinition(
                existing.id(),
                existing.projectId(),
                name,
                normalizedName,
                this.requireInstructions(command.instructions()),
                command.outputSchema(),
                existing.createdAt(),
                Instant.now(this.clock)
        );
        return this.toDetails(this.agentDefinitionRepository.save(updated));
    }

    private void requireProject(final UUID projectId) {
        if (projectId == null || this.projectRepository.findById(projectId).isEmpty()) {
            throw new NotFoundException("PROJECT_NOT_FOUND", "Project was not found.");
        }
    }

    private String requireName(final String candidate) {
        if (candidate == null || candidate.trim().isBlank()) {
            throw new ValidationException("INVALID_AGENT_NAME", "Agent name is required.");
        }
        final String trimmed = candidate.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ValidationException("INVALID_AGENT_NAME", "Agent name must be at most 120 characters.");
        }
        return trimmed;
    }

    private String requireInstructions(final String instructions) {
        if (instructions == null || instructions.trim().isBlank()) {
            throw new ValidationException("INVALID_AGENT_INSTRUCTIONS", "Agent instructions are required.");
        }
        return instructions;
    }

    private List<AgentListItem> toListItems(final List<AgentDefinition> agents) {
        return agents.stream()
                .map(agent -> new AgentListItem(
                        agent.id(),
                        agent.projectId(),
                        agent.name(),
                        agent.createdAt(),
                        agent.updatedAt()
                ))
                .toList();
    }

    private AgentDetails toDetails(final AgentDefinition agent) {
        return new AgentDetails(
                agent.id(),
                agent.projectId(),
                agent.name(),
                agent.instructions(),
                agent.outputSchema(),
                agent.createdAt(),
                agent.updatedAt()
        );
    }
}
