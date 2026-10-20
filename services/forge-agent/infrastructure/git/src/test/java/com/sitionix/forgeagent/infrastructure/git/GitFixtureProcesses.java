package com.sitionix.forgeagent.infrastructure.git;

import com.sitionix.forgeagent.infrastructure.local.runtime.*;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Owned synthetic process group for command/pipe tests, not a deployment proof. */
final class GitFixtureProcesses extends RuntimeProcessLauncher {
    private final Path readerGate;
    private volatile boolean rootExitObserved;

    GitFixtureProcesses() { this(null); }
    GitFixtureProcesses(Path readerGate) {
        super(new RuntimeBoundaryProperties("/unused-fixture-helper"));
        this.readerGate = readerGate;
    }
    boolean rootExitObserved() { return rootExitObserved; }

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
        return new ManagedRuntimeProcess(readerGate == null ? process : gateRootExitOnRead(process), () -> stop(process));
    }

    private Process gateRootExitOnRead(Process process) {
        InputStream stdout = new FilterInputStream(process.getInputStream()) {
            private boolean signalled;
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                // JDK processExited() synchronizes on this same pipe before draining/closing it.
                // Release the waiting root only while the reader owns that monitor, so the
                // inherited child pipe really stays blocked after root exit on every run.
                synchronized (in) {
                    if (!signalled) { Files.createFile(readerGate); signalled = true; }
                    return in.read(bytes, offset, length);
                }
            }
        };
        return new Process() {
            @Override public InputStream getInputStream() { return stdout; }
            @Override public InputStream getErrorStream() { return process.getErrorStream(); }
            @Override public OutputStream getOutputStream() { return process.getOutputStream(); }
            @Override public int waitFor() throws InterruptedException { return process.waitFor(); }
            @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
                boolean exited = process.waitFor(timeout, unit);
                if (exited) rootExitObserved = true;
                return exited;
            }
            @Override public int exitValue() { return process.exitValue(); }
            @Override public boolean isAlive() { return process.isAlive(); }
            @Override public long pid() { return process.pid(); }
            @Override public ProcessHandle toHandle() { return process.toHandle(); }
            @Override public void destroy() { process.destroy(); }
        };
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
