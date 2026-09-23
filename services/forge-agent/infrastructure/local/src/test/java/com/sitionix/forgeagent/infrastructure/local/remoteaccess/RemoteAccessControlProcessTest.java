package com.sitionix.forgeagent.infrastructure.local.remoteaccess;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
class RemoteAccessControlProcessTest {
    @Test void stdinAndOutputAreBoundedAndNonzeroExitFails() throws Exception {
        assertThat(RemoteAccessControlProcess.execute(List.of("/usr/bin/cat"),"ACTIVE\n".getBytes(),Duration.ofSeconds(2))).isEqualTo("ACTIVE\n");
        assertThatThrownBy(() -> RemoteAccessControlProcess.execute(List.of("/usr/bin/false"),new byte[0],Duration.ofSeconds(2))).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> RemoteAccessControlProcess.execute(List.of("/usr/bin/yes"),new byte[0],Duration.ofSeconds(2))).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> RemoteAccessControlProcess.execute(List.of("/usr/bin/cat"),new byte[4097],Duration.ofSeconds(2))).isInstanceOf(Exception.class);
    }
    @Test void hangingProcessIsCancelledAtDeadline() {
        long started=System.nanoTime();
        assertThatThrownBy(() -> RemoteAccessControlProcess.execute(List.of("/usr/bin/sleep","30"),new byte[0],Duration.ofMillis(100)))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(3));
    }
    @Test void cancellationInterruptsControlCall() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> RemoteAccessControlProcess.execute(List.of("/usr/bin/sleep","30"),new byte[0],Duration.ofSeconds(5)))
                    .isInstanceOf(InterruptedException.class);
        } finally { Thread.interrupted(); }
    }
}
