package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.io.*;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class LocalRemoteAccessCommandTransport implements RemoteAccessCommandTransport {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(r -> {
        var thread=new Thread(r,"remote-command-timeouts");thread.setDaemon(true);return thread;
    });
    private final Semaphore slots=new Semaphore(16);
    private final RemoteAccessCredentialStore credentials;
    private final Starter starter;
    @FunctionalInterface interface Starter { Process start(List<String> command) throws IOException; }
    @Autowired public LocalRemoteAccessCommandTransport(RemoteAccessCredentialStore credentials) {
        this(credentials,command -> new ProcessBuilder(command).start());
    }
    LocalRemoteAccessCommandTransport(RemoteAccessCredentialStore credentials,Starter starter) {
        this.credentials=credentials;this.starter=starter;
    }
    @Override public RemoteAccessCommandExecution start(RemoteAccessSession session,RemoteAccessCommand command) {
        if (session.localRole()!=RemoteAccessRole.ACCESSOR || session.status()!=RemoteAccessSessionStatus.ACTIVE) {
            throw new IllegalArgumentException("Active accessor session required");
        }
        if (!slots.tryAcquire()) throw new IllegalStateException("Remote command capacity exceeded");
        RemoteAccessSshCredentials files=null;Process process=null;
        try(var key=credentials.read(session.localPrivateKeyReference())) {
            files=new RemoteAccessSshCredentials(session,key);
            process=starter.start(RemoteAccessSshCommand.control(session,files.key,files.knownHosts,"exec"));
            process.getOutputStream().write(JSON.writeValueAsBytes(command));
            process.getOutputStream().write('\n');process.getOutputStream().flush();
            return new Running(process,files,command.timeoutSeconds());
        } catch (IOException | RuntimeException failure) {
            if (process!=null) process.destroyForcibly();
            if (files!=null) try { files.close(); } catch (IOException ignored) { }
            slots.release();throw new IllegalStateException("Remote command unavailable");
        }
    }
    private final class Running implements RemoteAccessCommandExecution {
        private final Process process;
        private final RemoteAccessSshCredentials files;
        private final ScheduledFuture<?> deadline;
        private boolean released;
        private IOException cleanupFailure;
        Running(Process process,RemoteAccessSshCredentials files,int timeout) {
            this.process=process;this.files=files;
            deadline=DEADLINES.schedule(process::destroyForcibly,timeout+15L,TimeUnit.SECONDS);
            process.onExit().thenRun(this::release);
        }
        private synchronized void release() {
            if (released) return;
            deadline.cancel(false);
            try { files.close(); } catch (IOException failure) { cleanupFailure=failure; }
            finally { released=true;slots.release(); }
        }
        @Override public OutputStream stdin() { return process.getOutputStream(); }
        @Override public InputStream stdout() { return process.getInputStream(); }
        @Override public InputStream stderr() { return process.getErrorStream(); }
        @Override public int await() throws InterruptedException {
            try {
                int result=process.waitFor();release();
                if (cleanupFailure!=null) throw new IllegalStateException("Remote command credential cleanup incomplete");
                return result;
            } catch (InterruptedException cancelled) { close();Thread.currentThread().interrupt();throw cancelled; }
        }
        @Override public void close() {
            process.destroy();
            try { if (!process.waitFor(2,TimeUnit.SECONDS)) { process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS); } }
            catch (InterruptedException cancelled) { process.destroyForcibly();Thread.currentThread().interrupt(); }
            release();
            if (cleanupFailure!=null) throw new IllegalStateException("Remote command credential cleanup incomplete");
        }
    }
}
