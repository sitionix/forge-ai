package com.sitionix.forgeagent.infrastructure.local.runtime;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/** Pipe lifetime is not runtime lifetime; owned systemd cleanup must be acknowledged. */
public final class ManagedRuntimeProcess extends Process {
    private final Process pipe;
    private final Runnable stop;
    private boolean terminated;

    public ManagedRuntimeProcess(Process pipe, Runnable stop) {
        this.pipe = pipe;
        this.stop = stop;
    }

    public synchronized void terminateOwnedUnit() {
        if (terminated) return;
        stop.run();
        terminated = true;
        // Never acquire the stdin monitor: transport may be blocked writing it.
        if (pipe.isAlive()) pipe.toHandle().destroyForcibly();
    }

    @Override public OutputStream getOutputStream() { return pipe.getOutputStream(); }
    @Override public InputStream getInputStream() { return pipe.getInputStream(); }
    @Override public InputStream getErrorStream() { return pipe.getErrorStream(); }
    @Override public int waitFor() throws InterruptedException { return pipe.waitFor(); }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return pipe.waitFor(timeout, unit); }
    @Override public int exitValue() { return pipe.exitValue(); }
    @Override public boolean isAlive() { return pipe.isAlive(); }
    @Override public long pid() { return pipe.pid(); }
    @Override public ProcessHandle toHandle() { return pipe.toHandle(); }
    @Override public void destroy() { terminateOwnedUnit(); }
    @Override public Process destroyForcibly() { terminateOwnedUnit(); return this; }
}
