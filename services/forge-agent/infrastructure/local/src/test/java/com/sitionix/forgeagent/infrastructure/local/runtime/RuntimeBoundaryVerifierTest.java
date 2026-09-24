package com.sitionix.forgeagent.infrastructure.local.runtime;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeBoundaryVerifierTest {
    @Test void disabledStartupDoesNotNeedPrivilegedHelper() {
        var properties = RuntimeBoundaryProperties.disabled();
        var launcher = new RuntimeProcessLauncher(properties);
        new RuntimeBoundaryVerifier(properties, launcher).afterSingletonsInstantiated();
        assertThatThrownBy(() -> launcher.startCodex(Path.of("/tmp")))
            .isInstanceOf(IllegalStateException.class);
    }
    @Test void enabledMissingHelperFailsClosedWithoutFallback() {
        var properties = new RuntimeBoundaryProperties(true, "/missing-forge-helper");
        var launcher = new RuntimeProcessLauncher(properties);
        assertThatThrownBy(() -> new RuntimeBoundaryVerifier(properties, launcher).afterSingletonsInstantiated())
            .hasMessage("Runtime boundary unavailable");
        assertThatThrownBy(() -> launcher.startGit(List.of("git", "status")))
            .hasMessage("Runtime boundary unavailable");
    }
}
