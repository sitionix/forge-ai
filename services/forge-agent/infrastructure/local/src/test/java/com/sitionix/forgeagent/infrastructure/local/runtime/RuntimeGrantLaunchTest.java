package com.sitionix.forgeagent.infrastructure.local.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeGrantLaunchTest {
    @Test void startupEnvelopeContainsOnlyFixedGrantNamesAndNoCommandSyntax() throws Exception {
        var output = new ByteArrayOutputStream();
        RuntimeProcessLauncher.writeCodexEnvelope(output, Map.of(
                "FORGE_MCP_GRANT_0123456789ABCDEF0123456789ABCDEF", "synthetic-grant"));
        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo("{\"FORGE_MCP_GRANT_0123456789ABCDEF0123456789ABCDEF\":\"synthetic-grant\"}\n");
        assertThatThrownBy(() -> RuntimeProcessLauncher.writeCodexEnvelope(new ByteArrayOutputStream(),
                Map.of("PATH", "synthetic-grant"))).hasMessage("Runtime boundary unavailable");
    }

    @Test void disabledBoundaryNeverFallsBackToUnisolatedCodexForGrant() {
        var launcher = new RuntimeProcessLauncher(RuntimeBoundaryProperties.disabled());
        assertThatThrownBy(() -> launcher.startCodex(Path.of("/tmp"), Map.of(
                "FORGE_MCP_GRANT_0123456789ABCDEF0123456789ABCDEF", "synthetic-grant")))
                .hasMessage("Runtime boundary unavailable");
    }
}
