package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.McpRuntimeLaunchGrants;
import com.sitionix.forgeagent.infrastructure.local.runtime.ManagedRuntimeProcess;
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeProcessLauncher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultCodexAppServerProcessStarterTest {
    @Test void ordinaryCodexKeepsLegacyLaunchWhileMcpUsesIsolatedLaunch() throws Exception {
        Path workspace = Files.createTempDirectory("forge-stage4-starter-");
        try {
            var properties = mock(CodexAppServerProperties.class);
            when(properties.getCommand()).thenReturn(List.of("codex", "app-server", "--stdio"));
            var launcher = mock(RuntimeProcessLauncher.class);
            when(launcher.enabled()).thenReturn(true);
            when(launcher.startCodex(workspace)).thenReturn(mock(ManagedRuntimeProcess.class));
            when(launcher.startCodex(any(Path.class), any(Map.class)))
                    .thenReturn(mock(ManagedRuntimeProcess.class));
            var starter = new DefaultCodexAppServerProcessStarter(properties, launcher);

            assertThat(starter.start(workspace).process()).isNotNull();
            verify(launcher).startCodex(workspace);
            verify(launcher, never()).startCodex(any(Path.class), any(Map.class));

            var grants = new McpRuntimeLaunchGrants(Map.of(
                    "forge_0123456789ab4cde80123456789abcde", "synthetic-grant"));
            assertThat(starter.start(workspace, grants).process()).isNotNull();
            verify(launcher).startCodex(workspace, grants.environment());
        } finally {
            Files.deleteIfExists(workspace);
        }
    }

    @Test void mcpSelectionWithoutIsolationCannotUseOrdinaryProcessFallback() throws Exception {
        Path workspace = Files.createTempDirectory("forge-stage4-starter-");
        try {
            var properties = mock(CodexAppServerProperties.class);
            when(properties.getCommand()).thenReturn(List.of("codex", "app-server", "--stdio"));
            var launcher = mock(RuntimeProcessLauncher.class);
            when(launcher.enabled()).thenReturn(false);
            var starter = new DefaultCodexAppServerProcessStarter(properties, launcher);
            assertThatThrownBy(() -> starter.start(workspace, new McpRuntimeLaunchGrants(Map.of())))
                    .hasMessageContaining("Isolated Codex runtime is unavailable");
        } finally {
            Files.deleteIfExists(workspace);
        }
    }
}
