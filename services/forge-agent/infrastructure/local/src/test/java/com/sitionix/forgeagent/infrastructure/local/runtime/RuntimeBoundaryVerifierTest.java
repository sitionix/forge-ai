package com.sitionix.forgeagent.infrastructure.local.runtime;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeBoundaryVerifierTest {
    @Test void missingHelperFailsClosedWithoutFallback() {
        var properties = new RuntimeBoundaryProperties( "/missing-forge-helper");
        var launcher = new RuntimeProcessLauncher(properties);
        assertThatThrownBy(() -> new RuntimeBoundaryVerifier(properties, launcher).afterSingletonsInstantiated())
            .hasMessage("Runtime boundary unavailable");
        assertThatThrownBy(() -> launcher.startGit(List.of("git", "status")))
            .hasMessage("Runtime boundary unavailable");
    }
}
