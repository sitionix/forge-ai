package com.sitionix.forgeagent.infrastructure.git;

import com.sitionix.forgeagent.infrastructure.local.runtime.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Owned synthetic process group for command/pipe tests, not a deployment proof. */
final class GitFixtureProcesses extends RuntimeProcessLauncher {
    GitFixtureProcesses() { super(new RuntimeBoundaryProperties("/unused-fixture-helper")); }

    @Override public ManagedRuntimeProcess startGit(List<String> command) throws IOException {
        var invocation = new ArrayList<>(List.of("/usr/bin/python3", "-I", "-c",
                "import os,sys;os.setsid();sys.stdout.buffer.write(b'READY\\n');sys.stdout.flush();os.execvp(sys.argv[1],sys.argv[1:])"));
        invocation.addAll(command);
        var builder = new ProcessBuilder(invocation);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        byte[] ready = process.getInputStream().readNBytes(6);
        if (!java.util.Arrays.equals(ready, "READY\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            process.destroyForcibly();
            throw new IOException("Fixture startup unconfirmed");
        }
        return new ManagedRuntimeProcess(process, () -> stop(process));
    }

    private static void stop(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            Process signal = new ProcessBuilder("/bin/kill", "-TERM", "--", "-" + process.pid()).start();
            if (!signal.waitFor(2, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture stop unconfirmed");
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                signal = new ProcessBuilder("/bin/kill", "-KILL", "--", "-" + process.pid()).start();
                if (!signal.waitFor(2, TimeUnit.SECONDS) || !process.waitFor(2, TimeUnit.SECONDS))
                    throw new IllegalStateException("Fixture stop unconfirmed");
            }
        } catch (InterruptedException failure) {
            interrupted = true;
            throw new IllegalStateException("Fixture stop interrupted");
        } catch (IOException failure) { throw new IllegalStateException("Fixture stop unavailable"); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
