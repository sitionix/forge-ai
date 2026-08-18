package com.sitionix.forgeagent.infrastructure.local;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryCloneAttempt;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceState;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryWorkspaceReference;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspaceException;
import com.sitionix.forgeagent.domain.port.LocalProjectWorkspacePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalProjectWorkspaceAdapter implements LocalProjectWorkspacePort {

    private static final String FORGE_PROJECTS_DIRECTORY = "forge-projects";
    private static final String CLONE_ATTEMPTS_DIRECTORY = ".forge-clone-attempts";

    private final ForgeRootResolver forgeRootResolver;

    @Override
    public Map<UUID, ProjectRepositoryWorkspaceState> resolveRepositoryWorkspaceStates(final UUID projectId,
                                                                                       final List<ProjectRepositoryWorkspaceReference> repositories) {
        final Map<UUID, ProjectRepositoryWorkspaceState> workspaceStates = new LinkedHashMap<>();
        for (final ProjectRepositoryWorkspaceReference repository : repositories) {
            final Path repositoryPath = this.repositoryPath(projectId, repository);
            workspaceStates.put(repository.id(), new ProjectRepositoryWorkspaceState(repository.id(), repositoryPath, this.isCloned(repositoryPath)));
        }
        return workspaceStates;
    }

    @Override
    public ProjectRepositoryCloneAttempt prepareCloneAttempt(final UUID projectId, final ProjectRepositoryWorkspaceReference repository) {
        final Path finalPath = this.repositoryPath(projectId, repository);
        final Path attemptsRoot = this.cloneAttemptsRoot(projectId);
        try {
            Files.createDirectories(attemptsRoot);
            final Path stagingPath = attemptsRoot.resolve(repository.name() + "-" + UUID.randomUUID()).normalize();
            if (!stagingPath.startsWith(attemptsRoot)) {
                throw new LocalProjectWorkspaceException("Repository clone attempt resolves outside managed workspace.");
            }
            Files.createDirectory(stagingPath);
            return new ProjectRepositoryCloneAttempt(stagingPath, finalPath);
        } catch (final IOException exception) {
            throw new LocalProjectWorkspaceException("Failed to prepare Forge repository clone attempt.", exception);
        }
    }

    @Override
    public void finalizeCloneAttempt(final ProjectRepositoryCloneAttempt attempt) {
        final Path stagingPath = this.requireManagedStagingTarget(attempt.stagingPath());
        final Path finalPath = this.requireManagedFinalTarget(attempt.finalPath());
        try {
            if (Files.exists(finalPath)) {
                throw new LocalProjectWorkspaceException("Forge repository clone target already exists.");
            }
            Files.move(stagingPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException exception) {
            this.moveWithoutReplacing(stagingPath, finalPath, exception);
        } catch (final FileAlreadyExistsException exception) {
            throw new LocalProjectWorkspaceException("Forge repository clone target already exists.", exception);
        } catch (final IOException exception) {
            throw new LocalProjectWorkspaceException("Failed to finalize Forge repository clone attempt.", exception);
        }
    }

    private void moveWithoutReplacing(final Path stagingPath, final Path finalPath, final AtomicMoveNotSupportedException originalException) {
        try {
            if (Files.exists(finalPath)) {
                throw new LocalProjectWorkspaceException("Forge repository clone target already exists.");
            }
            Files.move(stagingPath, finalPath);
        } catch (final FileAlreadyExistsException exception) {
            throw new LocalProjectWorkspaceException("Forge repository clone target already exists.", exception);
        } catch (final IOException exception) {
            if (Files.exists(finalPath)) {
                throw new LocalProjectWorkspaceException("Forge repository clone target already exists.", exception);
            }
            throw new LocalProjectWorkspaceException("Failed to finalize Forge repository clone attempt.", originalException);
        }
    }

    @Override
    public void cleanupCloneAttempt(final ProjectRepositoryCloneAttempt attempt) {
        final Path stagingPath = this.requireManagedStagingTarget(attempt.stagingPath());
        if (!Files.exists(stagingPath)) {
            return;
        }
        this.deleteRecursively(stagingPath, "Failed to clean failed Forge repository clone attempt.");
    }

    private void deleteRecursively(final Path targetPath, final String failureMessage) {
        try (Stream<Path> paths = Files.walk(targetPath)) {
            final List<Path> orderedPaths = paths
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (final Path path : orderedPaths) {
                Files.deleteIfExists(path);
            }
        } catch (final IOException exception) {
            throw new LocalProjectWorkspaceException(failureMessage, exception);
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

    private Path cloneAttemptsRoot(final UUID projectId) {
        return this.projectWorkspace(projectId)
                .resolve(CLONE_ATTEMPTS_DIRECTORY)
                .normalize();
    }

    private boolean isCloned(final Path repositoryPath) {
        return Files.isDirectory(repositoryPath)
                && (Files.isDirectory(repositoryPath.resolve(".git")) || Files.isRegularFile(repositoryPath.resolve(".git")));
    }

    private Path requireManagedFinalTarget(final Path targetPath) {
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

    private Path requireManagedStagingTarget(final Path targetPath) {
        final Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        final Path forgeProjectsRoot = this.forgeRootResolver.resolveForgeRoot()
                .resolve(FORGE_PROJECTS_DIRECTORY)
                .toAbsolutePath()
                .normalize();
        final Path attemptsDirectoryName = Path.of(CLONE_ATTEMPTS_DIRECTORY);
        if (!normalizedTarget.startsWith(forgeProjectsRoot) || !normalizedTarget.getParent().endsWith(attemptsDirectoryName)) {
            throw new LocalProjectWorkspaceException("Forge repository clone attempt resolves outside managed workspace.");
        }
        return normalizedTarget;
    }

}
