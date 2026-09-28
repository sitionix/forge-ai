package com.sitionix.forgeproxyit.infra;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Protected synthetic files and normal typed operator login for existing proxy tests. */
public abstract class NexusManagementFixture {
    protected static final String OPERATOR = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final Path ROOT;
    static {
        try {
            ROOT = Files.createTempDirectory("forge-nexus-management-it");
            ROOT.toFile().deleteOnExit();
            write("operator", OPERATOR);
            byte[] service = new byte[32];
            java.util.Arrays.fill(service, (byte) 1);
            write("service", Base64.getUrlEncoder().withoutPadding().encodeToString(service));
        } catch (Exception exception) {
            throw new IllegalStateException("Synthetic protected fixture creation failed");
        }
    }
    private static void write(String name, String value) throws Exception {
        Path file = ROOT.resolve(name);
        Files.writeString(file, value);
        Files.setAttribute(file, "unix:mode", 0600);
        file.toFile().deleteOnExit();
    }
    @DynamicPropertySource
    static void protectedFiles(DynamicPropertyRegistry registry) {
        registry.add("forge.mcp.bootstrap-credential-file", () -> ROOT.resolve("operator").toString());
        registry.add("forge.mcp.agent-service-credential-file", () -> ROOT.resolve("service").toString());
        registry.add("forge.mcp.operator-origin", () -> "http://127.0.0.1:9099");
    }
}
