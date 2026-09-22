package com.sitionix.forgeagent.infrastructure.local;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_WRITE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import com.sitionix.forgeagent.domain.model.RemoteAccessPrivateKey;
import com.sitionix.forgeagent.domain.port.RemoteAccessCredentialStore;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class LocalRemoteAccessCredentialStore implements RemoteAccessCredentialStore {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(OWNER_READ, OWNER_WRITE);

    private final Path root;
    private final CredentialWriter writer;

    @Autowired
    public LocalRemoteAccessCredentialStore(
            @Value("${forge.agent.remote-access.credential-directory:${FORGE_RUNTIME_DIR:./var}/agent/remote-access/credentials}")
            final String credentialDirectory) {
        this(Path.of(credentialDirectory));
    }

    public LocalRemoteAccessCredentialStore(final Path root) {
        this(root, OutputStream::write);
    }

    LocalRemoteAccessCredentialStore(final Path root, final CredentialWriter writer) {
        this.root = Objects.requireNonNull(root, "Credential directory is required.").toAbsolutePath().normalize();
        this.writer = Objects.requireNonNull(writer, "Credential writer is required.");
    }

    @Override
    public UUID store(final UUID sessionId, final RemoteAccessPrivateKey privateKey) {
        Objects.requireNonNull(sessionId, "Session id is required.");
        Objects.requireNonNull(privateKey, "Private key is required.");
        this.prepareAndValidateRoot();
        final Path credential = this.credentialPath(sessionId);
        byte[] copiedBytes = privateKey.copyBytes();
        Object createdFileKey = null;
        boolean created = false;
        try (var channel = Files.newByteChannel(
                credential,
                Set.of(CREATE_NEW, WRITE),
                PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
             var output = Channels.newOutputStream(channel)) {
            created = true;
            createdFileKey = Files.readAttributes(credential, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey();
            this.writer.write(output, copiedBytes);
            output.flush();
            this.requireSecureCredential(credential);
            return sessionId;
        } catch (final FileAlreadyExistsException exception) {
            throw new IllegalStateException("Remote access credential already exists.", exception);
        } catch (final IOException exception) {
            if (created) {
                this.removeCreatedFileAfterFailure(credential, createdFileKey, exception);
            }
            throw new IllegalStateException("Failed to store remote access credential.", exception);
        } catch (final RuntimeException exception) {
            if (created) {
                this.removeCreatedFileAfterFailure(credential, createdFileKey, exception);
            }
            throw exception;
        } finally {
            Arrays.fill(copiedBytes, (byte) 0);
        }
    }

    @Override
    public RemoteAccessPrivateKey read(final UUID reference) {
        Objects.requireNonNull(reference, "Credential reference is required.");
        this.validateExistingRoot();
        final Path credential = this.credentialPath(reference);
        this.requireSecureCredential(credential);
        byte[] bytes = null;
        try (var input = Files.newInputStream(credential, READ, NOFOLLOW_LINKS)) {
            bytes = input.readAllBytes();
            return new RemoteAccessPrivateKey(bytes);
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to read remote access credential.", exception);
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    @Override
    public void delete(final UUID reference) {
        Objects.requireNonNull(reference, "Credential reference is required.");
        this.validateExistingRoot();
        final Path credential = this.credentialPath(reference);
        if (!Files.exists(credential, NOFOLLOW_LINKS)) {
            return;
        }
        this.requireSecureCredential(credential);
        try {
            Files.delete(credential);
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to delete remote access credential.", exception);
        }
    }

    private Path credentialPath(final UUID reference) {
        return this.root.resolve(reference.toString());
    }

    private void prepareAndValidateRoot() {
        this.rejectSymlinkAncestors();
        Path current = this.root.getRoot();
        try {
            for (final Path component : this.root) {
                current = current.resolve(component);
                if (Files.exists(current, NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(current)) {
                        throw new IllegalStateException("Credential directory must not contain symbolic links.");
                    }
                    continue;
                }
                try {
                    Files.createDirectory(current, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
                } catch (final FileAlreadyExistsException race) {
                    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS)) {
                        throw new IllegalStateException("Credential directory is unsafe.", race);
                    }
                }
            }
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to prepare credential directory.", exception);
        }
        this.validateExistingRoot();
    }

    private void validateExistingRoot() {
        this.rejectSymlinkAncestors();
        if (!Files.isDirectory(this.root, NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Credential directory is missing or is not a directory.");
        }
        try {
            if (!Files.getPosixFilePermissions(this.root, NOFOLLOW_LINKS).equals(DIRECTORY_PERMISSIONS)) {
                throw new IllegalStateException("Credential directory permissions must be 0700.");
            }
        } catch (final IOException | UnsupportedOperationException exception) {
            throw new IllegalStateException("Failed to validate credential directory permissions.", exception);
        }
    }

    private void rejectSymlinkAncestors() {
        Path current = this.root.getRoot();
        for (final Path component : this.root) {
            current = current.resolve(component);
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new IllegalStateException("Credential directory must not contain symbolic links.");
                }
                this.rejectWritableNonStickyDirectory(current);
            }
        }
    }

    private void rejectWritableNonStickyDirectory(final Path directory) {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            return;
        }
        try {
            final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS);
            if (!permissions.contains(GROUP_WRITE) && !permissions.contains(OTHERS_WRITE)) {
                return;
            }
            final int unixMode = (int) Files.getAttribute(directory, "unix:mode", NOFOLLOW_LINKS);
            if ((unixMode & 01000) == 0) {
                throw new IllegalStateException("Credential directory ancestor is writable and not sticky.");
            }
        } catch (final IOException | UnsupportedOperationException exception) {
            throw new IllegalStateException("Failed to validate credential directory ancestor.", exception);
        }
    }

    private void requireSecureCredential(final Path credential) {
        if (!Files.isRegularFile(credential, NOFOLLOW_LINKS) || Files.isSymbolicLink(credential)) {
            throw new IllegalStateException("Credential reference does not name a regular file.");
        }
        try {
            if (!Files.getPosixFilePermissions(credential, NOFOLLOW_LINKS).equals(FILE_PERMISSIONS)) {
                throw new IllegalStateException("Credential file permissions must be 0600.");
            }
        } catch (final IOException | UnsupportedOperationException exception) {
            throw new IllegalStateException("Failed to validate credential file permissions.", exception);
        }
    }

    private void removeCreatedFileAfterFailure(
            final Path credential, final Object createdFileKey, final Throwable originalFailure) {
        try {
            if (!Files.isRegularFile(credential, NOFOLLOW_LINKS) || Files.isSymbolicLink(credential)) {
                return;
            }
            final Object currentFileKey = Files.readAttributes(
                    credential, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey();
            if (createdFileKey != null && createdFileKey.equals(currentFileKey)) {
                Files.delete(credential);
            }
        } catch (final IOException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    @FunctionalInterface
    interface CredentialWriter {
        void write(OutputStream output, byte[] bytes) throws IOException;
    }
}
