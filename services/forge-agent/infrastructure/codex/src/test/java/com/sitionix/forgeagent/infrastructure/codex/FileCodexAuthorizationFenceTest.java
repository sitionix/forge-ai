package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationFence.Status;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCodexAuthorizationFenceTest {
    @TempDir Path workspace;
    private Path directory() { return workspace.resolve(".forge-codex-authorization"); }
    private Path file() { return directory().resolve(".forge-codex-authorization-fence"); }

    @Test void missing_file_requires_explicit_initial_login() {
        assertThat(new FileCodexAuthorizationFence(workspace).status()).isEqualTo(Status.UNINITIALIZED);
    }

    @Test void blocked_and_approved_states_survive_reconstruction_with_private_permissions() throws Exception {
        var fence = new FileCodexAuthorizationFence(workspace);
        fence.block();
        assertThat(new FileCodexAuthorizationFence(workspace).status()).isEqualTo(Status.BLOCKED);
        assertThat(Files.getPosixFilePermissions(directory())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(file())).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readString(file())).isEqualTo("blocked\n");
        fence.clear();
        assertThat(new FileCodexAuthorizationFence(workspace).status()).isEqualTo(Status.APPROVED);
        assertThat(Files.readString(file())).isEqualTo("clear\n");
    }

    @Test void corrupt_state_fails_closed() throws Exception {
        var fence = new FileCodexAuthorizationFence(workspace);
        fence.block();
        Files.writeString(file(), "invalid");
        assertThatThrownBy(fence::status).hasMessage("CODEX_AUTH_FENCE_UNAVAILABLE");
    }

    @Test void symlink_state_is_neither_read_nor_overwritten() throws Exception {
        var fence = new FileCodexAuthorizationFence(workspace);
        fence.status();
        Path target = workspace.resolve("target");
        Files.writeString(target, "clear\n");
        Files.createSymbolicLink(file(), target);
        assertThatThrownBy(fence::status).hasMessage("CODEX_AUTH_FENCE_UNAVAILABLE");
        assertThatThrownBy(fence::block).hasMessage("CODEX_AUTH_FENCE_UNAVAILABLE");
        assertThat(Files.readString(target)).isEqualTo("clear\n");
    }

    @Test void group_accessible_state_or_directory_is_rejected() throws Exception {
        var fence = new FileCodexAuthorizationFence(workspace);
        fence.block();
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-r-----"));
        assertThatThrownBy(fence::status).hasMessage("CODEX_AUTH_FENCE_UNAVAILABLE");
        assertThatThrownBy(fence::clear).hasMessage("CODEX_AUTH_FENCE_UNAVAILABLE");
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(directory(), PosixFilePermissions.fromString("rwxr-x---"));
        assertThatThrownBy(fence::status).hasMessage("CODEX_AUTH_FENCE_UNAVAILABLE");
    }

    @Test void shared_workspace_write_permissions_are_rejected() throws Exception {
        Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("rwxrwx---"));
        assertThatThrownBy(() -> new FileCodexAuthorizationFence(workspace).block())
                .hasMessage("CODEX_AUTH_FENCE_UNAVAILABLE");
    }
}
