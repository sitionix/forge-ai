package com.sitionix.forgeagent.infrastructure.local.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class RuntimeProcessLauncher {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ENVELOPE_BYTES = 262_144;
    private final RuntimeBoundaryProperties properties;
    private volatile boolean ready;

    public RuntimeProcessLauncher(RuntimeBoundaryProperties properties) { this.properties = properties; }
    public boolean enabled() { return properties.enabled(); }
    void markReady() { ready = true; }

    public ManagedRuntimeProcess startCodex(Path directory) throws IOException {
        return startCodex(directory, Map.of());
    }

    public ManagedRuntimeProcess startCodex(Path directory, Map<String, String> grantEnvironment) throws IOException {
        return start(List.of("codex", UUID.randomUUID().toString(), directory.toAbsolutePath().normalize().toString()),
                grantEnvironment);
    }

    public ManagedRuntimeProcess startGit(List<String> command) throws IOException {
        if (command.isEmpty() || !List.of("git", "/usr/bin/git").contains(command.getFirst())) throw unavailable();
        var arguments = new ArrayList<>(List.of("git", UUID.randomUUID().toString()));
        arguments.addAll(command.subList(1, command.size()));
        return start(arguments, null);
    }

    private ManagedRuntimeProcess start(List<String> arguments, Map<String, String> grantEnvironment) throws IOException {
        if (!properties.enabled() || !ready) throw unavailable();
        var command = new ArrayList<>(List.of("start"));
        command.addAll(arguments);
        String execution = arguments.get(1);
        Process process = helper(command).start();
        var managed = new ManagedRuntimeProcess(process, () -> stop(execution));
        if (grantEnvironment != null) {
            try {
                writeCodexEnvelope(process.getOutputStream(), grantEnvironment);
            } catch (IOException | RuntimeException failure) {
                try { managed.terminateOwnedUnit(); }
                catch (RuntimeException cleanupFailure) { /* Keep the public failure fixed and secret-free. */ }
                throw unavailable();
            }
        }
        return managed;
    }

    static void writeCodexEnvelope(OutputStream output, Map<String, String> grantEnvironment) throws IOException {
        if (grantEnvironment == null || grantEnvironment.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || !entry.getKey().matches("FORGE_MCP_GRANT_[0-9A-F]{32}")
                        || entry.getValue() == null || !entry.getValue().matches("[A-Za-z0-9_-]{1,256}")))
            throw unavailable();
        byte[] encoded = JSON.writeValueAsBytes(grantEnvironment);
        if (encoded.length > MAX_ENVELOPE_BYTES - 1) throw unavailable();
        output.write(encoded);
        output.write('\n');
        output.flush();
    }

    private void stop(String execution) {
        // Cancellation often arrives on an interrupted thread; complete bounded cleanup first.
        boolean interrupted = Thread.interrupted();
        Process process = null;
        try {
            process = helper(List.of("stop", execution))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
            while (true) {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) throw unavailable();
                    if (process.exitValue() != 0) throw unavailable();
                    return;
                } catch (InterruptedException exception) { interrupted = true; }
            }
        } catch (IOException exception) { throw unavailable(); }
        finally {
            if (process != null && process.isAlive()) process.toHandle().destroyForcibly();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    ProcessBuilder helper(List<String> arguments) {
        validateTrustedExecutable(Path.of(properties.helper()));
        var command = new ArrayList<>(List.of("/usr/bin/sudo", "-n", "--", properties.helper()));
        command.addAll(arguments);
        var builder = new ProcessBuilder(command);
        builder.directory(Path.of("/").toFile());
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("LANG", "C.UTF-8");
        return builder;
    }

    static void validateTrustedExecutable(Path path) {
        try {
            if (!path.isAbsolute() || !path.normalize().equals(path) || !Files.isRegularFile(path) || !Files.isExecutable(path)) throw unavailable();
            for (Path current = path; current != null; current = current.getParent()) {
                if (Files.isSymbolicLink(current)
                        || ((Number) Files.getAttribute(current, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue() != 0
                        || (((Number) Files.getAttribute(current, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 0022) != 0) throw unavailable();
            }
        } catch (IOException | UnsupportedOperationException exception) { throw unavailable(); }
    }

    static IllegalStateException unavailable() { return new IllegalStateException("Runtime boundary unavailable"); }
}
