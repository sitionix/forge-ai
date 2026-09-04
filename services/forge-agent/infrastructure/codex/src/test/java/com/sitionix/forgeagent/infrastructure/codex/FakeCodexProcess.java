package com.sitionix.forgeagent.infrastructure.codex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class FakeCodexProcess extends Process {

    private static final AtomicLong PIDS = new AtomicLong(10_000L);

    private final long pid = PIDS.incrementAndGet();
    private final PipedInputStream stdout = new PipedInputStream();
    private final PipedOutputStream serverStdout;
    private final PipedInputStream stdinReader = new PipedInputStream();
    private final PipedOutputStream stdin;
    private final PipedInputStream stderr = new PipedInputStream();
    private final PipedOutputStream serverStderr;
    private final BufferedReader requestReader;
    private final CountDownLatch exited = new CountDownLatch(1);
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final boolean exitOnDestroy;
    private final boolean exitOnForce;
    private volatile boolean destroyed;
    private volatile boolean forciblyDestroyed;

    FakeCodexProcess() {
        this(true, true);
    }

    FakeCodexProcess(final boolean exitOnDestroy, final boolean exitOnForce) {
        this.exitOnDestroy = exitOnDestroy;
        this.exitOnForce = exitOnForce;
        try {
            this.serverStdout = new PipedOutputStream(this.stdout);
            this.stdin = new PipedOutputStream(this.stdinReader);
            this.serverStderr = new PipedOutputStream(this.stderr);
            this.requestReader = new BufferedReader(new InputStreamReader(this.stdinReader, StandardCharsets.UTF_8));
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    String readRequest() {
        try {
            return this.requestReader.readLine();
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    int pendingClientRequestBytes() {
        try {
            return this.stdinReader.available();
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    void writeStdout(final String frame) {
        this.write(this.serverStdout, frame + "\n");
    }

    void writeStdoutWithoutNewline(final String frame) {
        this.write(this.serverStdout, frame);
    }

    void closeStdout() {
        try {
            this.serverStdout.close();
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    boolean destroyed() {
        return this.destroyed;
    }

    boolean forciblyDestroyed() {
        return this.forciblyDestroyed;
    }

    void terminateNow() {
        this.exit();
    }

    private void write(final PipedOutputStream stream, final String value) {
        try {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
            stream.flush();
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void exit() {
        if (this.alive.compareAndSet(true, false)) {
            try {
                this.serverStdout.close();
            } catch (final IOException ignored) {
            }
            try {
                this.serverStderr.close();
            } catch (final IOException ignored) {
            }
            this.exited.countDown();
        }
    }

    @Override
    public OutputStream getOutputStream() {
        return this.stdin;
    }

    @Override
    public InputStream getInputStream() {
        return this.stdout;
    }

    @Override
    public InputStream getErrorStream() {
        return this.stderr;
    }

    @Override
    public int waitFor() throws InterruptedException {
        this.exited.await();
        return 0;
    }

    @Override
    public boolean waitFor(final long timeout, final TimeUnit unit) throws InterruptedException {
        return this.exited.await(timeout, unit);
    }

    boolean awaitExit(final Duration timeout) throws InterruptedException {
        return this.exited.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int exitValue() {
        if (this.isAlive()) {
            throw new IllegalThreadStateException("process is alive");
        }
        return 0;
    }

    @Override
    public void destroy() {
        this.destroyed = true;
        if (this.exitOnDestroy) {
            this.exit();
        }
    }

    @Override
    public Process destroyForcibly() {
        this.forciblyDestroyed = true;
        if (this.exitOnForce) {
            this.exit();
        }
        return this;
    }

    @Override
    public boolean isAlive() {
        return this.alive.get();
    }

    @Override
    public long pid() {
        return this.pid;
    }
}
