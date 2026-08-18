package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.GitLocalRepositoryState;
import com.sitionix.forgeagent.domain.model.GitRemoteInspection;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryCloneAttempt;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryLink;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryView;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceState;
import com.sitionix.forgeagent.domain.port.GitExecutionException;
import com.sitionix.forgeagent.domain.port.GitOperationException;
import com.sitionix.forgeagent.domain.port.GitRemoteRejectedException;
import com.sitionix.forgeagent.domain.port.GitRepositoryPort;
import com.sitionix.forgeagent.domain.port.GitUnsafeRepositoryStateException;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspaceException;
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

@Service
@RequiredArgsConstructor
public class ProjectRepositoryUseCases {

    private final ProjectRepository projectRepository;
    private final ProjectRepositoryLinkRepository repositoryLinkRepository;
    private final GitRepositoryPort gitRepositoryPort;
    private final LocalProjectWorkspacePort localProjectWorkspacePort;
    private final Clock clock;

    public ProjectRepositoryView importRepository(final UUID projectId, final ImportProjectRepositoryCommand command) {
        this.requireProject(projectId);
        final String remoteUrl = this.requireRemoteUrl(command.remoteUrl());
        final GitRemoteInspection inspection = this.inspectRemote(remoteUrl);
        this.rejectDuplicateRepositoryName(projectId, inspection.name());
        final Instant now = Instant.now(this.clock);
        final ProjectRepositoryLink saved = this.repositoryLinkRepository.save(new ProjectRepositoryLink(UUID.randomUUID(), projectId, remoteUrl, now));
        return this.toView(projectId, saved, inspection.name(), false, null);
    }

    public List<ProjectRepositoryView> listProjectRepositories(final UUID projectId) {
        this.requireProject(projectId);
        final List<ProjectRepositoryLink> repositories = this.repositoryLinkRepository.findByProjectId(projectId);
        try {
            final List<ProjectRepositoryWorkspaceReference> references = repositories.stream()
                    .map(this::toWorkspaceReference)
                    .toList();
            final Map<UUID, ProjectRepositoryWorkspaceState> workspaceStates =
                    this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(projectId, references);
            return repositories.stream()
                    .map(repository -> this.toListedView(projectId, repository, workspaceStates.get(repository.id())))
                    .toList();
        } catch (final GitOperationException | LocalProjectWorkspaceException exception) {
            throw new InfrastructureExecutionException("PROJECT_REPOSITORY_STATE_RESOLUTION_FAILED",
                    "Project repository state could not be resolved.");
        }
    }

    public ProjectRepositoryView cloneRepository(final UUID projectId, final UUID repositoryId) {
        this.requireProject(projectId);
        final ProjectRepositoryLink repository = this.requireRepository(projectId, repositoryId);
        ProjectRepositoryCloneAttempt attempt = null;
        try {
            final ProjectRepositoryWorkspaceReference reference = this.toWorkspaceReference(repository);
            final Map<UUID, ProjectRepositoryWorkspaceState> workspaceStates =
                    this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(projectId, List.of(reference));
            final ProjectRepositoryWorkspaceState workspaceState = workspaceStates.get(repository.id());
            if (workspaceState != null && workspaceState.cloned()) {
                throw new ConflictException("PROJECT_REPOSITORY_ALREADY_CLONED", "Project repository is already cloned.");
            }
            attempt = this.localProjectWorkspacePort.prepareCloneAttempt(projectId, reference);
            this.gitRepositoryPort.clone(repository.remoteUrl(), attempt.stagingPath());
            final GitLocalRepositoryState gitState = this.gitRepositoryPort.inspectLocalRepository(attempt.stagingPath());
            if (!gitState.valid()) {
                throw new GitExecutionException("Git clone produced invalid checkout.");
            }
            this.localProjectWorkspacePort.finalizeCloneAttempt(attempt);
            return this.toView(projectId, repository, reference.name(), true, gitState);
        } catch (final GitOperationException exception) {
            this.cleanupCloneAttempt(attempt);
            throw new InfrastructureExecutionException("PROJECT_REPOSITORY_CLONE_FAILED", "Project repository clone failed.");
        } catch (final LocalProjectWorkspaceException exception) {
            this.cleanupCloneAttempt(attempt);
            throw new InfrastructureExecutionException("PROJECT_REPOSITORY_WORKSPACE_FAILED",
                    "Project repository workspace operation failed.");
        }
    }

    public ProjectRepositoryView pullRepository(final UUID projectId, final UUID repositoryId) {
        this.requireProject(projectId);
        final ProjectRepositoryLink repository = this.requireRepository(projectId, repositoryId);
        try {
            final ProjectRepositoryWorkspaceReference reference = this.toWorkspaceReference(repository);
            final ProjectRepositoryWorkspaceState workspaceState =
                    this.localProjectWorkspacePort.resolveRepositoryWorkspaceState(projectId, reference);
            if (workspaceState == null || !workspaceState.cloned()) {
                throw new ConflictException("PROJECT_REPOSITORY_NOT_CLONED", "Project repository is not cloned.");
            }
            final GitLocalRepositoryState initialState = this.gitRepositoryPort.inspectLocalRepository(workspaceState.path());
            this.requirePullAllowed(initialState);
            final GitLocalRepositoryState finalState = this.gitRepositoryPort.pullFastForward(workspaceState.path());
            return this.toView(projectId, repository, reference.name(), true, finalState);
        } catch (final GitUnsafeRepositoryStateException exception) {
            throw this.pullBlocked();
        } catch (final GitOperationException exception) {
            throw new InfrastructureExecutionException("PROJECT_REPOSITORY_PULL_FAILED", "Project repository pull failed.");
        } catch (final LocalProjectWorkspaceException exception) {
            throw new InfrastructureExecutionException("PROJECT_REPOSITORY_WORKSPACE_FAILED",
                    "Project repository workspace operation failed.");
        }
    }

    public ProjectRepositoryView checkRepositoryUpdates(final UUID projectId, final UUID repositoryId) {
        this.requireProject(projectId);
        final ProjectRepositoryLink repository = this.requireRepository(projectId, repositoryId);
        try {
            final ProjectRepositoryWorkspaceReference reference = this.toWorkspaceReference(repository);
            final ProjectRepositoryWorkspaceState workspaceState =
                    this.localProjectWorkspacePort.resolveRepositoryWorkspaceState(projectId, reference);
            if (workspaceState == null || !workspaceState.cloned()) {
                throw new ConflictException("PROJECT_REPOSITORY_NOT_CLONED", "Project repository is not cloned.");
            }
            final GitLocalRepositoryState initialState = this.gitRepositoryPort.inspectLocalRepository(workspaceState.path());
            this.requireCheckUpdatesAllowed(initialState);
            final GitLocalRepositoryState finalState = this.gitRepositoryPort.checkUpdates(workspaceState.path());
            return this.toView(projectId, repository, reference.name(), true, finalState);
        } catch (final GitUnsafeRepositoryStateException exception) {
            throw new ConflictException("PROJECT_REPOSITORY_CHECK_UPDATES_BLOCKED",
                    "Project repository is not safe to check for updates.");
        } catch (final GitOperationException exception) {
            throw new InfrastructureExecutionException("PROJECT_REPOSITORY_CHECK_UPDATES_FAILED",
                    "Project repository check for updates failed.");
        } catch (final LocalProjectWorkspaceException exception) {
            throw new InfrastructureExecutionException("PROJECT_REPOSITORY_WORKSPACE_FAILED",
                    "Project repository workspace operation failed.");
        }
    }

    private void requireProject(final UUID projectId) {
        this.projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("PROJECT_NOT_FOUND", "Project was not found."));
    }

    private ProjectRepositoryLink requireRepository(final UUID projectId, final UUID repositoryId) {
        final ProjectRepositoryLink repository = this.repositoryLinkRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("PROJECT_REPOSITORY_NOT_FOUND", "Project repository was not found."));
        if (!Objects.equals(repository.projectId(), projectId)) {
            throw new NotFoundException("PROJECT_REPOSITORY_NOT_FOUND", "Project repository was not found.");
        }
        return repository;
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
        } catch (final GitRemoteRejectedException exception) {
            throw new ValidationException("INVALID_REPOSITORY_URL", "Repository remote is not reachable.");
        } catch (final GitOperationException exception) {
            throw new InfrastructureExecutionException("PROJECT_REPOSITORY_REMOTE_INSPECTION_FAILED",
                    "Project repository remote inspection failed.");
        }
    }

    private void cleanupCloneAttempt(final ProjectRepositoryCloneAttempt attempt) {
        if (attempt == null) {
            return;
        }
        try {
            this.localProjectWorkspacePort.cleanupCloneAttempt(attempt);
        } catch (final LocalProjectWorkspaceException cleanupException) {
            throw new InfrastructureExecutionException("PROJECT_REPOSITORY_CLONE_CLEANUP_FAILED",
                    "Project repository clone cleanup failed.");
        }
    }

    private void rejectDuplicateRepositoryName(final UUID projectId, final String repositoryName) {
        final boolean duplicate;
        try {
            duplicate = this.repositoryLinkRepository.findByProjectId(projectId).stream()
                    .map(ProjectRepositoryLink::remoteUrl)
                    .map(this.gitRepositoryPort::resolveRepositoryName)
                    .anyMatch(repositoryName::equals);
        } catch (final GitOperationException exception) {
            throw new InfrastructureExecutionException("PROJECT_REPOSITORY_NAME_RESOLUTION_FAILED",
                    "Project repository name could not be resolved.");
        }
        if (duplicate) {
            throw new ConflictException("PROJECT_REPOSITORY_NAME_EXISTS", "Project repository name already exists.");
        }
    }

    private void requirePullAllowed(final GitLocalRepositoryState gitState) {
        if (gitState == null || !gitState.valid() || !gitState.pullAllowed()) {
            throw this.pullBlocked();
        }
    }

    private void requireCheckUpdatesAllowed(final GitLocalRepositoryState gitState) {
        if (gitState == null || !gitState.valid() || !gitState.checkUpdatesAllowed()) {
            throw new ConflictException("PROJECT_REPOSITORY_CHECK_UPDATES_BLOCKED",
                    "Project repository is not safe to check for updates.");
        }
    }

    private ConflictException pullBlocked() {
        return new ConflictException("PROJECT_REPOSITORY_PULL_BLOCKED", "Project repository is not safe to pull.");
    }

    private ProjectRepositoryWorkspaceReference toWorkspaceReference(final ProjectRepositoryLink repository) {
        return new ProjectRepositoryWorkspaceReference(repository.id(), this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl()));
    }

    private ProjectRepositoryView toListedView(final UUID projectId,
                                               final ProjectRepositoryLink repository,
                                               final ProjectRepositoryWorkspaceState workspaceState) {
        final String name = this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl());
        final boolean cloned = workspaceState != null && workspaceState.cloned();
        final GitLocalRepositoryState gitState = cloned
                ? this.gitRepositoryPort.inspectLocalRepository(workspaceState.path())
                : null;
        return this.toView(projectId, repository, name, cloned, gitState);
    }

    private ProjectRepositoryView toView(final UUID projectId,
                                         final ProjectRepositoryLink repository,
                                         final String name,
                                         final boolean cloned,
                                         final GitLocalRepositoryState gitState) {
        return new ProjectRepositoryView(repository.id(), projectId, name, cloned, gitState, repository.createdAt());
    }
}
