package com.sitionix.forgeagent.it.infra;

import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeBoundaryVerifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Synthetic protected prerequisites for existing HTTP/DB tests, not OS isolation proof. */
public abstract class AgentManagementFixture {
    protected static final Path ROOT;
    static {
        try {
            ROOT = Files.createTempDirectory("forge-agent-management-it");
            ROOT.toFile().deleteOnExit();
            write("key", "active=test\nkey.test=" + Base64.getEncoder().encodeToString(new byte[32]) + "\n");
            write("database", "forge-it");
            Files.createDirectory(ROOT.resolve("workspace"));
            Files.setAttribute(ROOT.resolve("workspace"), "unix:mode", 02750);
        } catch (Exception exception) {
            throw new IllegalStateException("Synthetic protected fixture creation failed");
        }
    }
    protected static void write(String name, String contents) throws Exception {
        Path file = ROOT.resolve(name);
        Files.writeString(file, contents);
        Files.setAttribute(file, "unix:mode", 0600);
        file.toFile().deleteOnExit();
    }
    protected static final Path MANAGED_WORKSPACE = ROOT.resolve("workspace");
    @DynamicPropertySource
    static void protectedFiles(DynamicPropertyRegistry registry) {
        registry.add("forge.agent.workspace-root", MANAGED_WORKSPACE::toString);
        registry.add("forge.mcp.key-file", () -> ROOT.resolve("key").toString());
        registry.add("forge.mcp.database-credential-file", () -> ROOT.resolve("database").toString());
    }
    @MockBean protected RuntimeBoundaryVerifier runtimeBoundaryVerifier;
}
