package com.sitionix.forgeagent.infrastructure.local;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
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
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeBoundaryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LocalProjectWorkspaceAdapter implements LocalProjectWorkspacePort {

    private static final String FORGE_PROJECTS_DIRECTORY = "forge-projects";
    private static final String CLONE_ATTEMPTS_DIRECTORY = ".forge-clone-attempts";

    private final ForgeRootResolver forgeRootResolver;
    private final RuntimeBoundaryProperties boundary;

    public LocalProjectWorkspaceAdapter(ForgeRootResolver resolver) {
        this(resolver, RuntimeBoundaryProperties.disabled());
    }

    @Autowired
    public LocalProjectWorkspaceAdapter(ForgeRootResolver resolver, RuntimeBoundaryProperties boundary) {
        this.forgeRootResolver = resolver;
        this.boundary = boundary;
    }

    @Override
    public Path resolveProjectWorkspace(final UUID projectId) {
        final Path workspace = this.projectWorkspace(projectId).toAbsolutePath().normalize();
        try {
            if (this.boundary.enabled()) return this.prepareProtectedParent(workspace);
            Files.createDirectories(workspace);
            final Path managedRoot = this.forgeRootResolver.resolveForgeRoot()
                    .resolve(FORGE_PROJECTS_DIRECTORY)
                    .toRealPath();
            final Path resolvedWorkspace = workspace.toRealPath();
            if (!resolvedWorkspace.startsWith(managedRoot)) {
                throw new LocalProjectWorkspaceException("Forge project workspace resolves outside managed workspace.");
            }
            return resolvedWorkspace;
        } catch (final IOException exception) {
            throw new LocalProjectWorkspaceException("Failed to prepare Forge project workspace.", exception);
        }
    }

    @Override
    public Map<UUID, ProjectRepositoryWorkspaceState> resolveRepositoryWorkspaceStates(final UUID projectId,
                                                                                       final List<ProjectRepositoryWorkspaceReference> repositories) {
        final Map<UUID, ProjectRepositoryWorkspaceState> workspaceStates = new LinkedHashMap<>();
        for (final ProjectRepositoryWorkspaceReference repository : repositories) {
            workspaceStates.put(repository.id(), this.resolveRepositoryWorkspaceState(projectId, repository));
        }
        return workspaceStates;
    }

    @Override
    public ProjectRepositoryWorkspaceState resolveRepositoryWorkspaceState(final UUID projectId,
                                                                          final ProjectRepositoryWorkspaceReference repository) {
        final Path repositoryPath = this.repositoryPath(projectId, repository);
        final boolean cloned = this.isCloned(repositoryPath);
        return new ProjectRepositoryWorkspaceState(
                repository.id(),
                cloned ? this.requireManagedCheckout(projectId, repositoryPath) : repositoryPath,
                cloned
        );
    }

    @Override
    public ProjectRepositoryCloneAttempt prepareCloneAttempt(final UUID projectId, final ProjectRepositoryWorkspaceReference repository) {
        final Path finalPath = this.repositoryPath(projectId, repository);
        final Path attemptsRoot = this.cloneAttemptsRoot(projectId);
        try {
            if (this.boundary.enabled()) this.prepareProtectedParent(attemptsRoot);
            else Files.createDirectories(attemptsRoot);
            final Path stagingPath = attemptsRoot.resolve(repository.name() + "-" + UUID.randomUUID()).normalize();
            if (!stagingPath.startsWith(attemptsRoot)) {
                throw new LocalProjectWorkspaceException("Repository clone attempt resolves outside managed workspace.");
            }
            if (this.boundary.enabled()) {
                Files.createDirectory(stagingPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwx---")));
                Files.setAttribute(stagingPath, "unix:mode", 02770, LinkOption.NOFOLLOW_LINKS);
            } else Files.createDirectory(stagingPath);
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
            if (this.boundary.enabled()) {
                this.prepareProtectedParent(stagingPath.getParent());
                this.prepareProtectedParent(finalPath.getParent());
                if (Files.isSymbolicLink(stagingPath)) throw new IOException("Unsafe staging target");
            }
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
        if (this.boundary.enabled()) {
            try {
                this.prepareProtectedParent(targetPath.getParent());
                try (var parent = Files.newDirectoryStream(targetPath.getParent())) {
                    if (!(parent instanceof SecureDirectoryStream<Path> secure))
                        throw new IOException("Secure workspace cleanup unavailable");
                    try (var directory = secure.newDirectoryStream(targetPath.getFileName(), LinkOption.NOFOLLOW_LINKS)) {
                        deleteSecureContents(directory);
                    }
                    secure.deleteDirectory(targetPath.getFileName());
                }
                return;
            } catch (IOException exception) { throw new LocalProjectWorkspaceException(failureMessage, exception); }
        }
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

    /** Relative descriptor operations remain anchored if runtime renames a nested path. */
    static void deleteSecureContents(SecureDirectoryStream<Path> directory) throws IOException {
        for (Path entry : directory) {
            Path name = entry.getFileName();
            var attributes = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (attributes.isDirectory()) {
                try (var child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    deleteSecureContents(child);
                }
                directory.deleteDirectory(name);
            } else directory.deleteFile(name);
        }
    }

    private Path prepareProtectedParent(Path directory) throws IOException {
        Path managed = this.forgeRootResolver.resolveForgeRoot().resolve(FORGE_PROJECTS_DIRECTORY).toAbsolutePath().normalize();
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(managed) || !managed.toRealPath().equals(managed)) throw new IOException("Unsafe workspace parent");
        int uid = ((Number)Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue();
        int gid = ((Number)Files.getAttribute(managed, "unix:gid", LinkOption.NOFOLLOW_LINKS)).intValue();
        Path current = managed;
        validateProtectedParent(current, uid, gid);
        for (Path part : managed.relativize(target)) {
            current = current.resolve(part);
            try {
                Files.createDirectory(current, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-x---")));
                Files.setAttribute(current, "unix:mode", 02750, LinkOption.NOFOLLOW_LINKS);
            } catch (FileAlreadyExistsException ignored) { }
            validateProtectedParent(current, uid, gid);
        }
        return target;
    }

    private static void validateProtectedParent(Path path, int uid, int gid) throws IOException {
        int owner = ((Number)Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue();
        int group = ((Number)Files.getAttribute(path, "unix:gid", LinkOption.NOFOLLOW_LINKS)).intValue();
        int mode = ((Number)Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || owner != uid || group != gid
                || (mode & 0022) != 0 || (mode & 02000) == 0)
            throw new IOException("Unsafe workspace parent");
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

    private Path requireManagedCheckout(final UUID projectId, final Path repositoryPath) {
        try {
            if (this.boundary.enabled()) this.prepareProtectedParent(this.projectWorkspace(projectId));
            final Path projectWorkspace = this.projectWorkspace(projectId).toRealPath();
            final Path resolvedRepository = repositoryPath.toRealPath();
            if (!resolvedRepository.startsWith(projectWorkspace)) {
                throw new LocalProjectWorkspaceException("Forge repository checkout resolves outside project workspace.");
            }
            return resolvedRepository;
        } catch (final IOException exception) {
            throw new LocalProjectWorkspaceException("Forge repository checkout could not be resolved.", exception);
        }
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
