package com.sitionix.forgeagent.infrastructure.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryCloneTarget;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspaceException;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspacePort;
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
            throw new LocalProjectWorkspaceException("Failed to create Forge project workspace.", exception);
        }
    }

    @Override
    public void cleanupCloneTarget(final ProjectRepositoryCloneTarget target) {
        final Path targetPath = this.requireManagedTarget(target.path());
        if (!Files.exists(targetPath)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(targetPath)) {
            final List<Path> orderedPaths = paths
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (final Path path : orderedPaths) {
                Files.deleteIfExists(path);
            }
        } catch (final IOException exception) {
            throw new LocalProjectWorkspaceException("Failed to clean failed Forge repository clone target.", exception);
        }
    }

    private Path repositoryPath(final UUID projectId, final ProjectRepositoryWorkspaceReference repository) {
        if (repository.name() == null || repository.name().isBlank()) {
            throw new LocalProjectWorkspaceException("Repository name is required for Forge project workspace resolution.");
        }
        final Path workspace = this.projectWorkspace(projectId);
        final Path repositoryPath = workspace.resolve(repository.name()).normalize();
        if (!repositoryPath.startsWith(workspace)) {
            throw new LocalProjectWorkspaceException("Repository name resolves outside Forge project workspace.");
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
                && (Files.isDirectory(repositoryPath.resolve(".git")) || Files.isRegularFile(repositoryPath.resolve(".git")));
    }

    private Path requireManagedTarget(final Path targetPath) {
        final Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        final Path forgeProjectsRoot = this.forgeRootResolver.resolveForgeRoot()
                .resolve(FORGE_PROJECTS_DIRECTORY)
                .toAbsolutePath()
                .normalize();
        if (!normalizedTarget.startsWith(forgeProjectsRoot)) {
            throw new LocalProjectWorkspaceException("Forge repository clone target resolves outside managed workspace.");
        }
        return normalizedTarget;
    }

}
