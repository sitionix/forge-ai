package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.library.LibraryContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Internal process/protocol boundary shared by inventory, definition and manifest reads. */
final class CodexAgentDefinitionReader {
    private static final int MAX_OUTPUT = 1048576;
    private static final int MAX_INPUT = 131072;
    private static final int MAX_HELPER = 16384;
    private static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long CLEANUP_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path globalHome;
    private final Supplier<String> helper;

    CodexAgentDefinitionReader(Path globalHome) {
        this(globalHome, CodexAgentDefinitionReader::bundledHelper);
    }

    CodexAgentDefinitionReader(Path globalHome, Supplier<String> helper) {
        this.globalHome = globalHome;
        this.helper = helper;
    }

    JsonNode query(LibraryContext context, String resolveId, boolean manifest) throws Exception {
        validateContext(context);
        var request = JSON.createObjectNode().put("projectRoot", context.cwd().toString());
        if (resolveId != null) request.put("resolveId", resolveId);
        if (manifest) request.put("resourceManifest", true);
        var roots = request.putArray("roots");
        context.workspaceRoots().forEach(path -> roots.add(path.toString()));
        if (globalHome != null) request.put("globalHome", globalHome.toString());
        byte[] input = JSON.writeValueAsBytes(request);
        if (input.length > MAX_INPUT) throw new IOException("Reader input limit exceeded");
        String script = helper.get();
        if (script.getBytes(StandardCharsets.UTF_8).length > MAX_HELPER) throw new IOException("Reader script limit exceeded");
        return execute(context.cwd(), script, input);
    }

    private JsonNode execute(Path project, String script, byte[] input) throws Exception {
        Process process = null;
        FutureTask<byte[]> reading = null;
        long deadline = System.nanoTime() + BUDGET_NANOS;
        try {
            // A fixed installed interpreter, independent of PATH, is used for every operation.
            var builder = new ProcessBuilder("/usr/bin/python3", "-I", "-c", script)
                    .directory(project.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            builder.environment().put("LANG", "C.UTF-8");
            process = builder.start();
            final Process owned = process;
            reading = new FutureTask<>(() -> {
                try (var stdin = owned.getOutputStream()) { stdin.write(input); }
                try (var stdout = owned.getInputStream()) { return stdout.readNBytes(MAX_OUTPUT + 1); }
            });
            Thread.startVirtualThread(reading);
            byte[] output = reading.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (output.length > MAX_OUTPUT) throw new IOException("Reader output limit exceeded");
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) throw new TimeoutException();
            if (process.exitValue() != 0) throw new IOException("Reader process failed");
            JsonNode response = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output);
            if (response == null || !response.path("items").isArray() || !response.path("diagnostics").isArray()
                    || response.path("items").size() > 512 || response.path("diagnostics").size() > 16
                    || !response.path("runtimeCompatible").isBoolean()) throw new IOException("Invalid reader response");
            if (!response.path("runtimeCompatible").booleanValue()) throw new UnsupportedRuntimeException();
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            // Preserve interruption while still waiting for the owned helper to be reaped.
            boolean interrupted = Thread.interrupted();
            try {
                if (process != null && process.isAlive()) {
                    CodexProcessTree.capture(process).terminateTree();
                    if (!process.waitFor(CLEANUP_NANOS, TimeUnit.NANOSECONDS))
                        throw new IOException("Reader cleanup could not be confirmed");
                }
            } catch (InterruptedException cleanupInterrupted) {
                interrupted = true;
                if (process != null) CodexProcessTree.capture(process).terminateTree();
            } finally {
                if (reading != null) reading.cancel(true);
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime() - CLEANUP_NANOS;
        if (remaining <= 0) throw new TimeoutException();
        return remaining;
    }

    private void validateContext(LibraryContext context) throws IOException {
        Path project = context.cwd();
        if (!trustedAbsolute(project) || context.workspaceRoots().size() > 32
                || context.workspaceRoots().stream().anyMatch(path -> !trustedAbsolute(path) || !path.startsWith(project))
                || globalHome != null && !trustedAbsolute(globalHome)) throw new IOException("Invalid reader workspace");
    }

    private static boolean trustedAbsolute(Path path) {
        return path != null && path.isAbsolute() && path.normalize().equals(path) && path.getParent() != null;
    }

    boolean ownsAgentPath(Path file, LibraryContext context) {
        return trustedAbsolute(file) && file.getFileName().toString().endsWith(".toml")
                && (context.workspaceRoots().stream().anyMatch(root -> file.getParent().equals(root.resolve(".codex/agents")))
                    || globalHome != null && file.getParent().equals(globalHome.resolve(".codex/agents")));
    }

    boolean ownsResourcePath(String identity, LibraryContext context) {
        Path file = Path.of(identity);
        return trustedAbsolute(file) && (file.startsWith(context.cwd()) || globalHome != null && file.startsWith(globalHome));
    }

    static String reference(String identity) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("codex\u0000agent-file\u0000" + identity).getBytes(StandardCharsets.UTF_8)));
    }

    private static String bundledHelper() {
        try (var input = CodexAgentDefinitionReader.class.getResourceAsStream("/codex/project_agent_inventory.py")) {
            if (input == null) throw new IOException("Reader resource missing");
            byte[] bytes = input.readNBytes(MAX_HELPER + 1);
            if (bytes.length > MAX_HELPER) throw new IOException("Reader script limit exceeded");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    static final class UnsupportedRuntimeException extends IOException { }
}
