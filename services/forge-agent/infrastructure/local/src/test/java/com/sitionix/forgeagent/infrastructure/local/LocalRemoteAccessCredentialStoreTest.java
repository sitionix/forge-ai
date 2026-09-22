package com.sitionix.forgeagent.infrastructure.local;

import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_READ;
import static java.nio.file.attribute.PosixFilePermission.GROUP_WRITE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.model.RemoteAccessPrivateKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalRemoteAccessCredentialStoreTest {

    private static final Set<java.nio.file.attribute.PosixFilePermission> DIRECTORY_PERMISSIONS =
            Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
    private static final Set<java.nio.file.attribute.PosixFilePermission> FILE_PERMISSIONS =
            Set.of(OWNER_READ, OWNER_WRITE);

    @Test
    void storesWithRestrictivePermissionsAndSurvivesAdapterRestart(@TempDir final Path temp) throws Exception {
        final Path root = temp.resolve("credentials");
        final UUID sessionId = UUID.randomUUID();

        final UUID reference;
        try (var privateKey = new RemoteAccessPrivateKey("private-material".getBytes())) {
            reference = new LocalRemoteAccessCredentialStore(root).store(sessionId, privateKey);
        }

        assertThat(reference).isEqualTo(sessionId);
        assertThat(Files.getPosixFilePermissions(root)).isEqualTo(DIRECTORY_PERMISSIONS);
        assertThat(Files.getPosixFilePermissions(root.resolve(sessionId.toString()))).isEqualTo(FILE_PERMISSIONS);
        try (var restored = new LocalRemoteAccessCredentialStore(root).read(reference)) {
            assertThat(restored.copyBytes()).isEqualTo("private-material".getBytes());
        }
    }

    @Test
    void refusesToOverwriteAnExistingCredential(@TempDir final Path temp) throws Exception {
        final UUID sessionId = UUID.randomUUID();
        final Path root = secureDirectory(temp.resolve("credentials"));
        final Path credential = root.resolve(sessionId.toString());
        Files.writeString(credential, "somebody-elses-key");
        Files.setPosixFilePermissions(credential, FILE_PERMISSIONS);

        try (var replacement = new RemoteAccessPrivateKey("replacement".getBytes())) {
            assertThatThrownBy(() -> new LocalRemoteAccessCredentialStore(root).store(sessionId, replacement))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("replacement");
        }
        assertThat(Files.readString(credential)).isEqualTo("somebody-elses-key");
    }

    @Test
    void deletesOnlyTheCredentialNamedByItsUuidReference(@TempDir final Path temp) throws Exception {
        final Path root = temp.resolve("credentials");
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final var store = new LocalRemoteAccessCredentialStore(root);
        try (var firstKey = new RemoteAccessPrivateKey("first".getBytes());
             var secondKey = new RemoteAccessPrivateKey("second".getBytes())) {
            store.store(first, firstKey);
            store.store(second, secondKey);
        }

        store.delete(first);

        assertThat(root.resolve(first.toString())).doesNotExist();
        assertThat(root.resolve(second.toString())).exists();
    }

    @Test
    void rejectsSymlinkRootsAndCredentialFiles(@TempDir final Path temp) throws Exception {
        final Path realRoot = secureDirectory(temp.resolve("real"));
        final Path linkedRoot = temp.resolve("linked");
        Files.createSymbolicLink(linkedRoot, realRoot);
        final UUID sessionId = UUID.randomUUID();
        try (var key = new RemoteAccessPrivateKey("secret".getBytes())) {
            assertThatThrownBy(() -> new LocalRemoteAccessCredentialStore(linkedRoot).store(sessionId, key))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("secret");
        }

        final Path outside = temp.resolve("outside");
        Files.writeString(outside, "outside-key");
        Files.setPosixFilePermissions(outside, FILE_PERMISSIONS);
        Files.createSymbolicLink(realRoot.resolve(sessionId.toString()), outside);
        assertThatThrownBy(() -> new LocalRemoteAccessCredentialStore(realRoot).read(sessionId))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalRemoteAccessCredentialStore(realRoot).delete(sessionId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(outside)).isEqualTo("outside-key");
    }

    @Test
    void rejectsInsecureRootAndCredentialPermissions(@TempDir final Path temp) throws Exception {
        final UUID sessionId = UUID.randomUUID();
        final Path insecureRoot = Files.createDirectory(temp.resolve("insecure"));
        Files.setPosixFilePermissions(insecureRoot, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ));
        assertThat(Files.getPosixFilePermissions(insecureRoot)).contains(GROUP_READ);
        try (var key = new RemoteAccessPrivateKey("secret".getBytes())) {
            assertThatThrownBy(() -> new LocalRemoteAccessCredentialStore(insecureRoot).store(sessionId, key))
                    .isInstanceOf(IllegalStateException.class);
        }

        final Path root = secureDirectory(temp.resolve("secure"));
        final Path credential = Files.writeString(root.resolve(sessionId.toString()), "secret");
        Files.setPosixFilePermissions(credential, Set.of(OWNER_READ, OWNER_WRITE, GROUP_READ));
        assertThatThrownBy(() -> new LocalRemoteAccessCredentialStore(root).read(sessionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    void partialWriteFailureRemovesOnlyTheNewFile(@TempDir final Path temp) throws Exception {
        final Path root = temp.resolve("credentials");
        final UUID failed = UUID.randomUUID();
        final UUID existing = UUID.randomUUID();
        secureDirectory(root);
        final Path existingFile = Files.writeString(root.resolve(existing.toString()), "existing-key");
        Files.setPosixFilePermissions(existingFile, FILE_PERMISSIONS);
        final var store = new LocalRemoteAccessCredentialStore(root, (output, bytes) -> {
            output.write(bytes, 0, 1);
            throw new IOException("injected write failure");
        });

        try (var key = new RemoteAccessPrivateKey("secret".getBytes())) {
            assertThatThrownBy(() -> store.store(failed, key))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("secret");
        }

        assertThat(root.resolve(failed.toString())).doesNotExist();
        assertThat(Files.readString(existingFile)).isEqualTo("existing-key");
    }

    @Test
    void failureCleanupDoesNotDeleteAFileThatReplacedTheCreatedCredential(@TempDir final Path temp) throws Exception {
        final Path root = secureDirectory(temp.resolve("credentials"));
        final UUID sessionId = UUID.randomUUID();
        final Path credential = root.resolve(sessionId.toString());
        final Path movedCreatedFile = root.resolve("moved-created-file");
        final var store = new LocalRemoteAccessCredentialStore(root, (output, bytes) -> {
            output.write(bytes, 0, 1);
            Files.move(credential, movedCreatedFile);
            Files.writeString(credential, "replacement-key");
            Files.setPosixFilePermissions(credential, FILE_PERMISSIONS);
            throw new IOException("injected write failure after replacement");
        });

        try (var key = new RemoteAccessPrivateKey("secret".getBytes())) {
            assertThatThrownBy(() -> store.store(sessionId, key)).isInstanceOf(IllegalStateException.class);
        }

        assertThat(Files.readString(credential)).isEqualTo("replacement-key");
    }

    @Test
    void finalPermissionValidationFailureRemovesTheFileCreatedByThisCall(@TempDir final Path temp) throws Exception {
        final Path root = secureDirectory(temp.resolve("credentials"));
        final UUID sessionId = UUID.randomUUID();
        final Path credential = root.resolve(sessionId.toString());
        final var store = new LocalRemoteAccessCredentialStore(root, (output, bytes) -> {
            output.write(bytes);
            Files.setPosixFilePermissions(credential, Set.of(OWNER_READ, OWNER_WRITE, GROUP_READ));
        });

        try (var key = new RemoteAccessPrivateKey("secret".getBytes())) {
            assertThatThrownBy(() -> store.store(sessionId, key)).isInstanceOf(IllegalStateException.class);
        }

        assertThat(credential).doesNotExist();
    }

    @Test
    void rejectsSymlinkInExistingAncestor(@TempDir final Path temp) throws Exception {
        final Path actualParent = secureDirectory(temp.resolve("actual-parent"));
        final Path linkedParent = temp.resolve("linked-parent");
        Files.createSymbolicLink(linkedParent, actualParent);

        try (var key = new RemoteAccessPrivateKey("secret".getBytes())) {
            assertThatThrownBy(() -> new LocalRemoteAccessCredentialStore(linkedParent.resolve("credentials"))
                    .store(UUID.randomUUID(), key)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(actualParent.resolve("credentials")).doesNotExist();
    }

    @Test
    void rejectsWritableExistingAncestorWithoutStickyBit(@TempDir final Path temp) throws Exception {
        final Path insecureParent = Files.createDirectory(temp.resolve("insecure-parent"));
        final var insecurePermissions = Set.of(
                OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, GROUP_WRITE, GROUP_EXECUTE);
        // Creation permissions are filtered by umask; set the negative-test mode explicitly.
        Files.setPosixFilePermissions(insecureParent, insecurePermissions);
        assertThat(insecureParent).isDirectory();
        assertThat(Files.getPosixFilePermissions(insecureParent)).contains(GROUP_WRITE);
        final int originalMode = (int) Files.getAttribute(insecureParent, "unix:mode");
        assertThat(originalMode & 01000).isZero();
        final Path root = insecureParent.resolve("credentials");

        try (var key = new RemoteAccessPrivateKey("secret".getBytes())) {
            assertThatThrownBy(() -> new LocalRemoteAccessCredentialStore(root).store(UUID.randomUUID(), key))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(root).doesNotExist();
        assertThat(insecureParent).isEmptyDirectory();
        assertThat(Files.getPosixFilePermissions(insecureParent)).isEqualTo(insecurePermissions);
        assertThat(Files.getAttribute(insecureParent, "unix:mode")).isEqualTo(originalMode);
    }

    private static Path secureDirectory(final Path path) throws IOException {
        return Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
    }
}
