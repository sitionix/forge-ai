package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.infrastructure.local.runtime.ManagedRuntimeProcess;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodexManagedRuntimeTest {
    @Test void treeUsesOwnedCleanupEvenAfterPipeAlreadyExited() throws Exception {
        Process pipe = new ProcessBuilder("/bin/true").start();
        pipe.waitFor();
        AtomicInteger stops = new AtomicInteger();
        var process = new ManagedRuntimeProcess(pipe, stops::incrementAndGet);
        CodexProcessTree.capture(process).terminateTree();
        assertThat(stops.get()).isEqualTo(1);
    }
}
