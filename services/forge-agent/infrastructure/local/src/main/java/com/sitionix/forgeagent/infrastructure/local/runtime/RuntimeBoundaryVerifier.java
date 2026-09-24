package com.sitionix.forgeagent.infrastructure.local.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** Startup proof, and an explicit protected-file probe for the later auth/key wiring. */
@Component
public final class RuntimeBoundaryVerifier implements SmartInitializingSingleton {
    private final RuntimeBoundaryProperties properties;
    private final RuntimeProcessLauncher launcher;
    private final ObjectMapper mapper = new ObjectMapper();

    public RuntimeBoundaryVerifier(RuntimeBoundaryProperties properties, RuntimeProcessLauncher launcher) {
        this.properties = properties;
        this.launcher = launcher;
    }

    @Override public void afterSingletonsInstantiated() {
        if (!properties.enabled()) return;
        verify(List.of(), true);
        launcher.markReady();
    }

    /** Does not read or return credential contents. Every supplied path must already exist. */
    public void verifyProtectedPaths(List<Path> protectedPaths) {
        if (!properties.enabled()) throw RuntimeProcessLauncher.unavailable();
        verify(List.copyOf(protectedPaths), false);
    }

    private void verify(List<Path> paths, boolean reconcile) {
        Process process = null;
        try {
            int controlUid = ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue();
            for (Path path : paths) validateProtectedPath(path, controlUid);
            process = launcher.helper(List.of("probe")).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] request = mapper.writeValueAsBytes(Map.of("protected_paths", paths.stream().map(Path::toString).toList(),
                    "control_pid", ProcessHandle.current().pid(), "reconcile", reconcile));
            if (request.length > 16384) throw RuntimeProcessLauncher.unavailable();
            try (var input = process.getOutputStream()) { input.write(request); }
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) throw RuntimeProcessLauncher.unavailable();
            byte[] output = process.getInputStream().readNBytes(8193);
            if (output.length > 8192) throw RuntimeProcessLauncher.unavailable();
            var report = mapper.readTree(output);
            if (report.size() != 6 || !report.path("runtimeUid").isIntegralNumber()
                    || report.path("runtimeUid").intValue() <= 0 || report.path("runtimeUid").intValue() == controlUid
                    || !report.path("controlUid").isIntegralNumber() || report.path("controlUid").intValue() != controlUid)
                throw RuntimeProcessLauncher.unavailable();
            for (String key : List.of("protectedPathsDenied", "processAliasesDenied", "environmentClean", "ownedCleanupConfirmed"))
                if (!report.path(key).isBoolean() || !report.path(key).booleanValue()) throw RuntimeProcessLauncher.unavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw RuntimeProcessLauncher.unavailable();
        } catch (Exception exception) { throw RuntimeProcessLauncher.unavailable(); }
        finally { if (process != null && process.isAlive()) process.toHandle().destroyForcibly(); }
    }

    private static void validateProtectedPath(Path path, int controlUid) throws java.io.IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw RuntimeProcessLauncher.unavailable();
        for (Path current = path; current != null; current = current.getParent()) {
            int uid = ((Number) Files.getAttribute(current, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue();
            int mode = ((Number) Files.getAttribute(current, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
            if (Files.isSymbolicLink(current) || (uid != 0 && uid != controlUid) || (mode & 0022) != 0
                    || (current.equals(path) && ((mode & 0077) != 0 || ((Number)Files.getAttribute(current,"unix:nlink")).intValue() != 1)))
                throw RuntimeProcessLauncher.unavailable();
        }
    }
}
