package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ProjectRepositoryLink;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceState;
import com.sitionix.forgeagent.domain.port.GitRepositoryPort;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspacePort;
import com.sitionix.forgeagent.domain.port.ProjectRepositoryLinkRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExecutionWorkspaceResolver {

    private final ProjectRepositoryLinkRepository repositoryLinkRepository;
    private final LocalProjectWorkspacePort localProjectWorkspacePort;
    private final GitRepositoryPort gitRepositoryPort;

    public ExecutionWorkspace resolve(final UUID projectId,
                                      final UUID repositoryId,
                                      final List<UUID> workflowRepositoryIds) {
        try {
            return this.resolveWorkspace(projectId, repositoryId, workflowRepositoryIds);
        } catch (final ExecutionWorkspaceException exception) {
            throw exception;
        } catch (final RuntimeException exception) {
            throw new ExecutionWorkspaceException("Forge execution workspace could not be resolved.", exception);
        }
    }

    private ExecutionWorkspace resolveWorkspace(final UUID projectId,
                                                final UUID repositoryId,
                                                final List<UUID> workflowRepositoryIds) {
        final Path projectWorkspace = this.localProjectWorkspacePort.resolveProjectWorkspace(projectId);
        final List<UUID> repositoryIds = List.copyOf(workflowRepositoryIds);
        final Map<UUID, ProjectRepositoryLink> repositories = this.projectRepositories(projectId);
        final List<ProjectRepositoryWorkspaceReference> references = new ArrayList<>();
        for (final UUID selectedRepositoryId : repositoryIds) {
            final ProjectRepositoryLink repository = repositories.get(selectedRepositoryId);
            if (repository == null) {
                throw new ExecutionWorkspaceException("Workflow repository does not belong to the owning project.");
            }
            references.add(new ProjectRepositoryWorkspaceReference(
                    repository.id(),
                    this.gitRepositoryPort.resolveRepositoryName(repository.remoteUrl())
            ));
        }
        final Map<UUID, ProjectRepositoryWorkspaceState> states =
                this.localProjectWorkspacePort.resolveRepositoryWorkspaceStates(projectId, references);
        final List<Path> roots = repositoryIds.stream()
                .map(id -> this.requireAvailable(states.get(id)))
                .toList();

        if (repositoryId == null) {
            return new ExecutionWorkspace(projectWorkspace, roots);
        }
        if (!repositoryIds.contains(repositoryId)) {
            throw new ExecutionWorkspaceException("Scoped repository is not present in the workflow repository snapshot.");
        }
        final ProjectRepositoryLink targetRepository = repositories.get(repositoryId);
        if (targetRepository == null) {
            throw new ExecutionWorkspaceException("Scoped repository does not belong to the owning project.");
        }
        final Path repositoryWorkspace = this.requireAvailable(states.get(repositoryId));
        return new ExecutionWorkspace(repositoryWorkspace, List.of(repositoryWorkspace));
    }

    private Map<UUID, ProjectRepositoryLink> projectRepositories(final UUID projectId) {
        final Map<UUID, ProjectRepositoryLink> repositories = new HashMap<>();
        for (final ProjectRepositoryLink repository : this.repositoryLinkRepository.findByProjectId(projectId)) {
            repositories.put(repository.id(), repository);
        }
        return repositories;
    }

    private Path requireAvailable(final ProjectRepositoryWorkspaceState state) {
        if (state == null || !state.cloned()) {
            throw new ExecutionWorkspaceException("Required Forge repository checkout is unavailable.");
        }
        return state.path();
    }
}
