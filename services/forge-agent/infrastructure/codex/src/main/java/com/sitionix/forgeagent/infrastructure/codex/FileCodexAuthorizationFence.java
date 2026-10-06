package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.domain.port.LlmAuthorizationFence;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Agent/control-owned state outside CODEX_HOME, inaccessible to the runtime account. */
@Component
final class FileCodexAuthorizationFence implements LlmAuthorizationFence {
    private final Path directory;
    private final Path workspace;
    private final Path file;
    private static final Set<PosixFilePermission> MODE = PosixFilePermissions.fromString("rw-------");

    @Autowired FileCodexAuthorizationFence(CodexRuntimeWorkspace workspace) {
        this(workspace.routingWorkspace().cwd());
    }
    FileCodexAuthorizationFence(Path directory) {
        this.workspace = directory.toAbsolutePath().normalize();
        this.directory = this.workspace.resolve(".forge-codex-authorization");
        this.file = this.directory.resolve(".forge-codex-authorization-fence");
    }

    @Override public synchronized Status status() {
        try {
            validateDirectory();
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Status.UNINITIALIZED;
            validateFile();
            String state = Files.readString(file, StandardCharsets.US_ASCII);
            if (!Set.of("blocked\n", "clear\n").contains(state)) throw new java.io.IOException();
            return state.equals("blocked\n") ? Status.BLOCKED : Status.APPROVED;
        } catch (Exception failure) { throw unavailable(); }
    }
    @Override public void block() { write("blocked\n"); }
    @Override public void clear() { write("clear\n"); }

    private synchronized void write(String state) {
        Path staging = null;
        try {
            validateDirectory();
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) validateFile();
            staging = Files.createTempFile(directory, ".forge-codex-fence-", ".tmp", PosixFilePermissions.asFileAttribute(MODE));
            try (var channel = FileChannel.open(staging, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var bytes = ByteBuffer.wrap(state.getBytes(StandardCharsets.US_ASCII));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            staging = null;
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        } catch (Exception failure) { throw unavailable(); }
        finally { if (staging != null) try { Files.deleteIfExists(staging); } catch (Exception ignored) { } }
    }
    private void validateDirectory() throws java.io.IOException {
        var parent = Files.readAttributes(workspace, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!parent.isDirectory() || !parent.owner().equals(Files.getOwner(Path.of("/proc/self")))
                || parent.permissions().contains(PosixFilePermission.GROUP_WRITE)
                || parent.permissions().contains(PosixFilePermission.OTHERS_WRITE)) throw new java.io.IOException();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try (var channel = FileChannel.open(workspace, StandardOpenOption.READ)) { channel.force(true); }
        }
        var info = Files.readAttributes(directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!info.isDirectory() || !info.owner().equals(Files.getOwner(Path.of("/proc/self")))
                || !info.permissions().equals(PosixFilePermissions.fromString("rwx------"))) throw new java.io.IOException();
        for (Path ancestor = directory.getParent(); ancestor != null; ancestor = ancestor.getParent())
            if (Files.isSymbolicLink(ancestor)) throw new java.io.IOException();
    }
    private void validateFile() throws java.io.IOException {
        var info = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!info.isRegularFile() || !info.permissions().equals(MODE) || info.size() > 8
                || !info.owner().equals(Files.getOwner(directory))
                || ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() != 1)
            throw new java.io.IOException();
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("CODEX_AUTH_FENCE_UNAVAILABLE"); }
}
