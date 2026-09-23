package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.RemoteAccessCredentialStore;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Real local processes exercise stream handling; these are NOT SSH/systemd E2E. */
class LocalRemoteAccessCommandTransportTest {
    static RemoteAccessCredentialStore store() {
        return new RemoteAccessCredentialStore() {
            public UUID store(UUID id,RemoteAccessPrivateKey key) { throw new UnsupportedOperationException(); }
            public RemoteAccessPrivateKey read(UUID id) { return new RemoteAccessPrivateKey("test-private".getBytes()); }
            public void delete(UUID id) { throw new UnsupportedOperationException(); }
        };
    }
    static RemoteAccessSession active() { return LocalRemoteAccessPairingTransportTest.session(RemoteAccessRole.ACCESSOR).activate(Instant.now()); }
    @Test void streamsLargeIndependentOutputsAndStdinWithRealNonzeroExit() throws Exception {
        var paths=new ArrayList<Path>();
        var transport=new LocalRemoteAccessCommandTransport(store(),argv -> {
            assertThat(argv.getLast()).isEqualTo("exec");assertThat(argv).contains("IdentityAgent=none","StrictHostKeyChecking=yes");
            paths.add(Path.of(argv.get(argv.indexOf("-i")+1)).getParent());
            return new ProcessBuilder("python3","-c","import sys,json; h=json.loads(sys.stdin.buffer.readline()); data=sys.stdin.buffer.read(); sys.stdout.buffer.write(data*50000); sys.stdout.flush(); sys.stderr.buffer.write(b'e'*200000); sys.exit(7)").start();
        });
        try(var running=transport.start(active(),new RemoteAccessCommand(List.of("/bin/cat"),"/workspace",30));
            var readers=Executors.newVirtualThreadPerTaskExecutor()) {
            var out=readers.submit(() -> running.stdout().readAllBytes());
            var err=readers.submit(() -> running.stderr().readAllBytes());
            running.stdin().write("abc".getBytes());running.stdin().close();
            assertThat(running.await()).isEqualTo(7);
            assertThat(out.get(5,TimeUnit.SECONDS)).isEqualTo("abc".repeat(50000).getBytes());
            assertThat(err.get(5,TimeUnit.SECONDS)).hasSize(200000);
        }
        assertThat(paths).allSatisfy(path -> assertThat(path).doesNotExist());
    }
    @Test void cancellationStopsProcessAndFailureNeverRetries() throws Exception {
        var process=new java.util.concurrent.atomic.AtomicReference<Process>();
        var calls=new AtomicInteger();
        var transport=new LocalRemoteAccessCommandTransport(store(),argv -> {
            calls.incrementAndGet();var value=new ProcessBuilder("sleep","120").start();process.set(value);return value;
        });
        var running=transport.start(active(),new RemoteAccessCommand(List.of("/bin/sleep","120"),"/workspace",120));
        running.close();
        assertThat(process.get().isAlive()).isFalse();assertThat(calls.get()).isEqualTo(1);
        var broken=new LocalRemoteAccessCommandTransport(store(),argv -> { calls.incrementAndGet();throw new java.io.IOException("private diagnostic"); });
        assertThatThrownBy(() -> broken.start(active(),new RemoteAccessCommand(List.of("/bin/true"),"/workspace",1)))
            .hasMessage("Remote command unavailable").hasNoCause();
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void inactiveSessionCannotStartAnyProcess() {
        var transport=new LocalRemoteAccessCommandTransport(store(),argv -> {throw new AssertionError("No fallback execution");});
        var session=active().requestRevoke(Instant.now());
        assertThatThrownBy(() -> transport.start(session,new RemoteAccessCommand(List.of("/bin/true"),"/workspace",1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
