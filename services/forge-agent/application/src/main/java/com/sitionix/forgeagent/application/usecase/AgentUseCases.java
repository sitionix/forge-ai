package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.application.graph.DependencyGraphValidator;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentDependency;
import com.sitionix.forgeagent.domain.model.AgentDependencySummary;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentListItem;
import com.sitionix.forgeagent.domain.model.NameNormalizer;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.AgentDependencyRepository;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentUseCases {

    private static final int MAX_NAME_LENGTH = 120;

    private final ProjectRepository projectRepository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final AgentDependencyRepository agentDependencyRepository;
    private final DependencyGraphValidator dependencyGraphValidator;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<AgentListItem> listProjectAgents(final UUID projectId) {
        this.requireProject(projectId);
        return this.toListItems(projectId, this.agentDefinitionRepository.findByProjectId(projectId));
    }

    @Transactional(readOnly = true)
    public AgentDetails getAgent(final UUID agentId) {
        final AgentDefinition agent = this.agentDefinitionRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundException("AGENT_NOT_FOUND", "Agent was not found."));
        final List<AgentDefinition> projectAgents = this.agentDefinitionRepository.findByProjectId(agent.projectId());
        final Map<UUID, AgentDefinition> byId = projectAgents.stream()
                .collect(Collectors.toMap(AgentDefinition::id, Function.identity()));
        final List<AgentDependencySummary> dependencies = this.agentDependencyRepository.findDependsOnIds(agent.id())
                .stream()
                .map(byId::get)
                .filter(dependency -> dependency != null)
                .map(dependency -> new AgentDependencySummary(dependency.id(), dependency.name()))
                .toList();
        return new AgentDetails(
                agent.id(),
                agent.projectId(),
                agent.name(),
                agent.instructions(),
                agent.outputSchema(),
                dependencies,
                agent.createdAt(),
                agent.updatedAt()
        );
    }

    @Transactional
    public AgentDetails createAgent(final UUID projectId, final SaveAgentCommand command) {
        this.lockProject(projectId);
        final String name = this.requireName(command.name());
        final String normalizedName = NameNormalizer.normalize(name);
        if (this.agentDefinitionRepository.existsByProjectIdAndNormalizedName(projectId, normalizedName)) {
            throw new ConflictException("DUPLICATE_AGENT_NAME", "An agent with this name already exists in this project.");
        }
        final String instructions = this.requireInstructions(command.instructions());
        final Set<UUID> dependencyIds = this.normalizedDependencyIds(command.dependsOnAgentIds());
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

        final List<AgentDefinition> projectAgents = new ArrayList<>(this.agentDefinitionRepository.findByProjectId(projectId));
        projectAgents.add(newAgent);
        this.validateDependencyIds(projectId, newAgent.id(), dependencyIds);
        this.validateResultingGraph(projectId, projectAgents, newAgent.id(), dependencyIds);

        final AgentDefinition saved = this.agentDefinitionRepository.save(newAgent);
        this.agentDependencyRepository.replaceDependencies(saved.id(), dependencyIds);
        return this.getAgent(saved.id());
    }

    @Transactional
    public AgentDetails updateAgent(final UUID agentId, final SaveAgentCommand command) {
        final AgentDefinition existing = this.agentDefinitionRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundException("AGENT_NOT_FOUND", "Agent was not found."));
        this.lockProject(existing.projectId());
        final AgentDefinition current = this.agentDefinitionRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundException("AGENT_NOT_FOUND", "Agent was not found."));
        final String name = this.requireName(command.name());
        final String normalizedName = NameNormalizer.normalize(name);
        if (this.agentDefinitionRepository.existsByProjectIdAndNormalizedNameExcludingId(current.projectId(), normalizedName, current.id())) {
            throw new ConflictException("DUPLICATE_AGENT_NAME", "An agent with this name already exists in this project.");
        }
        final Set<UUID> dependencyIds = this.normalizedDependencyIds(command.dependsOnAgentIds());
        this.validateDependencyIds(current.projectId(), current.id(), dependencyIds);

        final AgentDefinition updated = new AgentDefinition(
                current.id(),
                current.projectId(),
                name,
                normalizedName,
                this.requireInstructions(command.instructions()),
                command.outputSchema(),
                current.createdAt(),
                Instant.now(this.clock)
        );
        final List<AgentDefinition> projectAgents = this.agentDefinitionRepository.findByProjectId(current.projectId())
                .stream()
                .map(agent -> agent.id().equals(updated.id()) ? updated : agent)
                .toList();
        this.validateResultingGraph(current.projectId(), projectAgents, updated.id(), dependencyIds);

        this.agentDefinitionRepository.save(updated);
        this.agentDependencyRepository.replaceDependencies(updated.id(), dependencyIds);
        return this.getAgent(updated.id());
    }

    private void requireProject(final UUID projectId) {
        if (projectId == null || this.projectRepository.findById(projectId).isEmpty()) {
            throw new NotFoundException("PROJECT_NOT_FOUND", "Project was not found.");
        }
    }

    private Project lockProject(final UUID projectId) {
        if (projectId == null) {
            throw new NotFoundException("PROJECT_NOT_FOUND", "Project was not found.");
        }
        return this.projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new NotFoundException("PROJECT_NOT_FOUND", "Project was not found."));
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

    private Set<UUID> normalizedDependencyIds(final Collection<UUID> dependencyIds) {
        if (dependencyIds == null || dependencyIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(dependencyIds);
    }

    private void validateDependencyIds(final UUID projectId, final UUID agentId, final Set<UUID> dependencyIds) {
        if (dependencyIds.contains(agentId)) {
            throw new ValidationException("SELF_DEPENDENCY", "An agent cannot depend on itself.");
        }
        if (dependencyIds.isEmpty()) {
            return;
        }
        final List<AgentDefinition> dependencies = this.agentDefinitionRepository.findByIds(dependencyIds);
        final Map<UUID, AgentDefinition> byId = dependencies.stream()
                .collect(Collectors.toMap(AgentDefinition::id, Function.identity()));
        for (final UUID dependencyId : dependencyIds) {
            final AgentDefinition dependency = byId.get(dependencyId);
            if (dependency == null) {
                throw new ValidationException("UNKNOWN_DEPENDENCY", "Dependencies must reference existing agents.");
            }
            if (!projectId.equals(dependency.projectId())) {
                throw new ConflictException("CROSS_PROJECT_DEPENDENCY", "Dependencies must belong to the same project.");
            }
        }
    }

    private void validateResultingGraph(final UUID projectId,
                                        final List<AgentDefinition> projectAgents,
                                        final UUID changedAgentId,
                                        final Set<UUID> changedDependencyIds) {
        final List<AgentDependency> resultingDependencies = new ArrayList<>(this.agentDependencyRepository.findByProjectId(projectId)
                .stream()
                .filter(dependency -> !dependency.agentId().equals(changedAgentId))
                .toList());
        changedDependencyIds.forEach(dependencyId -> resultingDependencies.add(new AgentDependency(changedAgentId, dependencyId)));
        this.dependencyGraphValidator.validate(projectId, projectAgents, resultingDependencies);
    }

    private List<AgentListItem> toListItems(final UUID projectId, final List<AgentDefinition> agents) {
        final Map<UUID, AgentDefinition> byId = agents.stream()
                .collect(Collectors.toMap(AgentDefinition::id, Function.identity()));
        final Map<UUID, List<UUID>> dependenciesByAgent = this.agentDependencyRepository.findByProjectId(projectId)
                .stream()
                .collect(Collectors.groupingBy(AgentDependency::agentId, Collectors.mapping(AgentDependency::dependsOnAgentId, Collectors.toList())));
        return agents.stream()
                .map(agent -> new AgentListItem(
                        agent.id(),
                        agent.projectId(),
                        agent.name(),
                        dependenciesByAgent.getOrDefault(agent.id(), List.of()).stream()
                                .map(byId::get)
                                .filter(dependency -> dependency != null)
                                .map(dependency -> new AgentDependencySummary(dependency.id(), dependency.name()))
                                .toList(),
                        agent.createdAt(),
                        agent.updatedAt()
                ))
                .toList();
    }
}
