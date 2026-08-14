package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.NameNormalizer;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectUseCases {

    private static final int MAX_NAME_LENGTH = 120;

    private final ProjectRepository projectRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Project> listProjects() {
        return this.projectRepository.findAllOrdered();
    }

    @Transactional
    public Project createProject(final CreateProjectCommand command) {
        final String name = this.requireName(command.name());
        final String normalizedName = NameNormalizer.normalize(name);
        if (this.projectRepository.existsByNormalizedName(normalizedName)) {
            throw new ConflictException("DUPLICATE_PROJECT_NAME", "A project with this name already exists.");
        }
        final Instant now = Instant.now(this.clock);
        return this.projectRepository.save(new Project(UUID.randomUUID(), name, normalizedName, now, now));
    }

    @Transactional
    public void deleteProject(final UUID projectId) {
        this.projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("PROJECT_NOT_FOUND", "Project was not found."));
        if (this.workflowRunRepository.existsActiveByProjectId(projectId)) {
            throw new ConflictException("PROJECT_HAS_ACTIVE_EXECUTIONS", "Project cannot be deleted while an execution is active.");
        }
        this.projectRepository.deleteById(projectId);
    }

    private String requireName(final String candidate) {
        if (candidate == null || candidate.trim().isBlank()) {
            throw new ValidationException("INVALID_PROJECT_NAME", "Project name is required.");
        }
        final String trimmed = candidate.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ValidationException("INVALID_PROJECT_NAME", "Project name must be at most 120 characters.");
        }
        return trimmed;
    }
}
