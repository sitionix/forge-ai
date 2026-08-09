package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.application.graph.NodeGraphValidator;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.NameNormalizer;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.port.AgentDefinitionRepository;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkflowUseCases {

    private static final int MAX_NAME_LENGTH = 120;

    private final ProjectRepository projectRepository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final WorkflowRepository workflowRepository;
    private final NodeGraphValidator nodeGraphValidator;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Workflow> listProjectWorkflows(final UUID projectId) {
        this.requireProject(projectId);
        return this.workflowRepository.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public Workflow getWorkflow(final UUID workflowId) {
        return this.workflowRepository.findById(workflowId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
    }

    @Transactional
    public Workflow createWorkflow(final UUID projectId, final CreateWorkflowCommand command) {
        this.requireProject(projectId);
        final String name = this.requireName(command.name());
        final String normalizedName = NameNormalizer.normalize(name);
        if (this.workflowRepository.existsByProjectIdAndNormalizedName(projectId, normalizedName)) {
            throw new ConflictException("DUPLICATE_WORKFLOW_NAME", "A workflow with this name already exists in this project.");
        }
        final Instant now = Instant.now(this.clock);
        return this.workflowRepository.save(new Workflow(
                UUID.randomUUID(),
                projectId,
                name,
                normalizedName,
                List.of(),
                now,
                now
        ));
    }

    @Transactional
    public Workflow updateWorkflow(final UUID workflowId, final SaveWorkflowCommand command) {
        final Workflow identity = this.workflowRepository.findById(workflowId)
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
        this.workflowRepository.findByIdForUpdate(identity.id())
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
        final Workflow current = this.workflowRepository.findById(identity.id())
                .orElseThrow(() -> new NotFoundException("WORKFLOW_NOT_FOUND", "Workflow was not found."));
        final String name = this.requireName(command.name());
        final String normalizedName = NameNormalizer.normalize(name);
        if (this.workflowRepository.existsByProjectIdAndNormalizedNameExcludingId(current.projectId(), normalizedName, current.id())) {
            throw new ConflictException("DUPLICATE_WORKFLOW_NAME", "A workflow with this name already exists in this project.");
        }
        final List<Node> requestedNodes = command.nodes() == null ? List.of() : command.nodes();
        final List<Node> normalizedNodes = this.nodeGraphValidator.validateAndNormalize(
                current.projectId(),
                requestedNodes,
                this.agentDefinitionRepository.findByIds(targetIds(requestedNodes))
        );
        return this.workflowRepository.save(new Workflow(
                current.id(),
                current.projectId(),
                name,
                normalizedName,
                normalizedNodes,
                current.createdAt(),
                Instant.now(this.clock)
        ));
    }

    private void requireProject(final UUID projectId) {
        if (projectId == null || this.projectRepository.findById(projectId).isEmpty()) {
            throw new NotFoundException("PROJECT_NOT_FOUND", "Project was not found.");
        }
    }

    private String requireName(final String candidate) {
        if (candidate == null || candidate.trim().isBlank()) {
            throw new ValidationException("INVALID_WORKFLOW_NAME", "Workflow name is required.");
        }
        final String trimmed = candidate.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ValidationException("INVALID_WORKFLOW_NAME", "Workflow name must be at most 120 characters.");
        }
        return trimmed;
    }

    private static Collection<UUID> targetIds(final List<Node> nodes) {
        final Set<UUID> targetIds = new LinkedHashSet<>();
        nodes.stream()
                .map(Node::targetId)
                .filter(targetId -> targetId != null)
                .forEach(targetIds::add);
        return targetIds;
    }
}
