package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.model.ProjectRepositoryCloneTarget;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspacePort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalProjectWorkspaceAdapter implements LocalProjectWorkspacePort {

    private static final String FORGE_PROJECTS_DIRECTORY = "forge-projects";

    private final ForgeRootResolver forgeRootResolver;

    @Override
    public Map<UUID, Boolean> resolveCloneStates(final UUID projectId, final List<ProjectRepositoryWorkspaceReference> repositories) {
        final Map<UUID, Boolean> cloneStates = new LinkedHashMap<>();
        for (final ProjectRepositoryWorkspaceReference repository : repositories) {
            cloneStates.put(repository.id(), this.isCloned(this.repositoryPath(projectId, repository)));
        }
        return cloneStates;
    }

    @Override
    public ProjectRepositoryCloneTarget resolveCloneTarget(final UUID projectId, final ProjectRepositoryWorkspaceReference repository) {
        return new ProjectRepositoryCloneTarget(this.repositoryPath(projectId, repository));
    }

    @Override
    public void ensureProjectWorkspace(final UUID projectId) {
        try {
            Files.createDirectories(this.projectWorkspace(projectId));
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to create Forge project workspace.", exception);
        }
    }

    private Path repositoryPath(final UUID projectId, final ProjectRepositoryWorkspaceReference repository) {
        if (repository.name() == null || repository.name().isBlank()) {
            throw new IllegalArgumentException("Repository name is required for Forge project workspace resolution.");
        }
        final Path workspace = this.projectWorkspace(projectId);
        final Path repositoryPath = workspace.resolve(repository.name()).normalize();
        if (!repositoryPath.startsWith(workspace)) {
            throw new IllegalArgumentException("Repository name resolves outside Forge project workspace.");
        }
        return repositoryPath;
    }

    private Path projectWorkspace(final UUID projectId) {
        return this.forgeRootResolver.resolveForgeRoot()
                .resolve(FORGE_PROJECTS_DIRECTORY)
                .resolve(projectId.toString())
                .normalize();
    }

    private boolean isCloned(final Path repositoryPath) {
        return Files.isDirectory(repositoryPath)
                && Files.isDirectory(repositoryPath.resolve(".git"));
    }

}
