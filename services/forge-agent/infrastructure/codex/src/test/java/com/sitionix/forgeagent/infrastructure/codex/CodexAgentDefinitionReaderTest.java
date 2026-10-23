package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.library.*;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CodexAgentDefinitionReaderTest {
    @TempDir Path project;

    private CodexAgentDefinitionReader reader(String script) {
        return new CodexAgentDefinitionReader(null, () -> script);
    }

    private LibraryContext context() {
        return new LibraryContext(null, null, project, List.of(project));
    }

    @Test void timeoutTerminatesTheOwnedHelperBeforeReturning() throws Exception {
        var reader = reader("import os,time\nopen('helper.pid','w').write(str(os.getpid()))\ntime.sleep(30)\n");
        assertThrows(TimeoutException.class, () -> reader.query(context(), null, true));
        long pid = Long.parseLong(Files.readString(project.resolve("helper.pid")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void interruptionPreservesTheFlagAndReapsTheHelper() throws Exception {
        var reader = reader("import os,time\nopen('helper.pid','w').write(str(os.getpid()))\ntime.sleep(30)\n");
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try { reader.query(context(), null, true); }
            catch (Throwable thrown) { failure.set(thrown); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(project.resolve("helper.pid")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(project.resolve("helper.pid")));
            caller.interrupt();
            caller.join(3000);
            assertFalse(caller.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
            assertTrue(interrupted.get());
            long pid = Long.parseLong(Files.readString(project.resolve("helper.pid")));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally { caller.interrupt(); caller.join(3000); }
    }

    @Test void oversizedOutputAndNonzeroExitFailClosedAndCleanUp() throws Exception {
        for (String body : List.of("print('x'*1048577)\ntime.sleep(30)", "sys.exit(2)")) {
            var reader = reader("import os,sys,time\nopen('helper.pid','w').write(str(os.getpid()))\n" + body);
            assertThrows(Exception.class, () -> reader.query(context(), null, false));
            long pid = Long.parseLong(Files.readString(project.resolve("helper.pid")));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test void incompatibleRuntimeIsExplicitForInventoryDefinitionAndManifest() {
        var reader = reader("print('{\"items\":[],\"diagnostics\":[\"RUNTIME_UNAVAILABLE\"],\"runtimeCompatible\":false}')");
        var adapter = new CodexNativeAgentDefinitionAdapter(reader);
        var workspace = new ExecutionWorkspace(project, List.of(project));
        assertEquals("AGENT_DEFINITION_RUNTIME_UNSUPPORTED", assertThrows(ConflictException.class,
                () -> adapter.resolve(UUID.randomUUID(), "a".repeat(64), workspace, null)).code());
        assertEquals("AGENT_DEFINITION_RUNTIME_UNSUPPORTED", assertThrows(ConflictException.class,
                () -> adapter.resources(workspace)).code());
        var inventory = new CodexProjectAgentInventory(null, reader).discover(context());
        assertTrue(inventory.diagnostics().contains(InventoryDiagnostic.RUNTIME_UNAVAILABLE));
    }

    @Test void rejectsMalformedEnvelopeTrailingDataAndInvalidManifestValues() {
        for (String response : List.of("{}", "{\"items\":[],\"diagnostics\":[],\"runtimeCompatible\":true} {}",
                "{\"items\":[],\"diagnostics\":[],\"runtimeCompatible\":true,\"manifest\":{\"/private/file\":42}}")) {
            var adapter = new CodexNativeAgentDefinitionAdapter(reader("print('" + response + "')"));
            var failure = assertThrows(ConflictException.class,
                    () -> adapter.resources(new ExecutionWorkspace(project, List.of(project))));
            assertEquals("AGENT_RESOURCE_UNAVAILABLE", failure.code());
            assertFalse(failure.getMessage().contains("/private"));
        }
    }

    @Test void rejectsEscapingRootsBeforeStartingAHelper() {
        var reader = reader("open('started','w').write('unexpected')");
        assertThrows(Exception.class, () -> reader.query(new LibraryContext(null, null, project,
                List.of(project.resolve("../outside"))), null, true));
        assertFalse(Files.exists(project.resolve("started")));
    }

    @Test void boundsInputBeforeStartingAHelper() {
        var reader = reader("open('started','w').write('unexpected')");
        var oversizedRoots = Collections.nCopies(32, project.resolve("x".repeat(5000)));
        assertThrows(Exception.class, () -> reader.query(new LibraryContext(null, null, project, oversizedRoots), null, true));
        assertFalse(Files.exists(project.resolve("started")));
    }
}
