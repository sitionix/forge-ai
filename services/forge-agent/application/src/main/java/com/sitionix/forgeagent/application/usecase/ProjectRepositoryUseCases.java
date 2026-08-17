package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.GitRemoteInspection;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryCloneTarget;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryLink;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryView;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import com.sitionix.forgeagent.domain.port.GitOperationException;
import com.sitionix.forgeagent.domain.port.GitRepositoryPort;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspacePort;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.ProjectRepositoryLinkRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectRepositoryUseCases {

    private final ProjectRepository projectRepository;
    private final ProjectRepositoryLinkRepository repositoryLinkRepository;
    private final GitRepositoryPort gitRepositoryPort;
    private final LocalProjectWorkspacePort localProjectWorkspacePort;
    private final Clock clock;

    @Transactional
    public ProjectRepositoryView importRepository(final UUID projectId, final ImportProjectRepositoryCommand command) {
        this.requireProject(projectId);
        final String remoteUrl = this.requireRemoteUrl(command.remoteUrl());
        final GitRemoteInspection inspection = this.inspectRemote(remoteUrl);
        this.rejectDuplicateRepositoryName(projectId, inspection.name());
        final Instant now = Instant.now(this.clock);
        final ProjectRepositoryLink saved = this.repositoryLinkRepository.save(new ProjectRepositoryLink(UUID.randomUUID(), projectId, remoteUrl, now));
        return this.toView(projectId, saved, inspection.name(), false);
    }

    @Transactional(readOnly = true)
    public List<ProjectRepositoryView> listProjectRepositories(final UUID projectId) {
        this.requireProject(projectId);
        final List<ProjectRepositoryLink> repositories = this.repositoryLinkRepository.findByProjectId(projectId);
        final List<ProjectRepositoryWorkspaceReference> references = repositories.stream()
                .map(this::toWorkspaceReference)
                .toList();
        final Map<UUID, Boolean> cloneStates = this.localProjectWorkspacePort.resolveCloneStates(projectId, references);
        return repositories.stream()
                .map(repository -> this.toView(projectId, repository, this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl()),
                        Boolean.TRUE.equals(cloneStates.get(repository.id()))))
                .toList();
    }

    @Transactional
    public ProjectRepositoryView cloneRepository(final UUID projectId, final UUID repositoryId) {
        this.requireProject(projectId);
        final ProjectRepositoryLink repository = this.repositoryLinkRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("PROJECT_REPOSITORY_NOT_FOUND", "Project repository was not found."));
        if (!Objects.equals(repository.projectId(), projectId)) {
            throw new NotFoundException("PROJECT_REPOSITORY_NOT_FOUND", "Project repository was not found.");
        }
        final ProjectRepositoryWorkspaceReference reference = this.toWorkspaceReference(repository);
        final Map<UUID, Boolean> cloneStates = this.localProjectWorkspacePort.resolveCloneStates(projectId, List.of(reference));
        if (Boolean.TRUE.equals(cloneStates.get(repository.id()))) {
            throw new ConflictException("PROJECT_REPOSITORY_ALREADY_CLONED", "Project repository is already cloned.");
        }
        final ProjectRepositoryCloneTarget target = this.localProjectWorkspacePort.resolveCloneTarget(projectId, reference);
        this.localProjectWorkspacePort.ensureProjectWorkspace(projectId);
        try {
            this.gitRepositoryPort.clone(repository.remoteUrl(), target.path());
        } catch (final GitOperationException exception) {
            throw new InfrastructureExecutionException("PROJECT_REPOSITORY_CLONE_FAILED", "Project repository clone failed.");
        }
        return this.toView(projectId, repository, reference.name(), true);
    }

    private void requireProject(final UUID projectId) {
        this.projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("PROJECT_NOT_FOUND", "Project was not found."));
    }

    private String requireRemoteUrl(final String candidate) {
        if (candidate == null || candidate.trim().isBlank()) {
            throw new ValidationException("INVALID_REPOSITORY_URL", "Repository URL is required.");
        }
        return candidate.trim();
    }

    private GitRemoteInspection inspectRemote(final String remoteUrl) {
        try {
            return this.gitRepositoryPort.inspectRemote(remoteUrl);
        } catch (final GitOperationException exception) {
            throw new ValidationException("INVALID_REPOSITORY_URL", "Repository remote is not reachable.");
        }
    }

    private void rejectDuplicateRepositoryName(final UUID projectId, final String repositoryName) {
        final boolean duplicate = this.repositoryLinkRepository.findByProjectId(projectId).stream()
                .map(ProjectRepositoryLink::remoteUrl)
                .map(this.gitRepositoryPort::resolveRepositoryName)
                .anyMatch(repositoryName::equals);
        if (duplicate) {
            throw new ConflictException("PROJECT_REPOSITORY_NAME_EXISTS", "Project repository name already exists.");
        }
    }

    private ProjectRepositoryWorkspaceReference toWorkspaceReference(final ProjectRepositoryLink repository) {
        return new ProjectRepositoryWorkspaceReference(repository.id(), this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl()));
    }

    private ProjectRepositoryView toView(final UUID projectId,
                                         final ProjectRepositoryLink repository,
                                         final String name,
                                         final boolean cloned) {
        return new ProjectRepositoryView(repository.id(), projectId, name, cloned, repository.createdAt());
    }
}
