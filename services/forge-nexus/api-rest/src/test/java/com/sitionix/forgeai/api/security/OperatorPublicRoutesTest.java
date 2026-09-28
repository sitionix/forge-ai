package com.sitionix.forgeai.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OperatorPublicRoutesTest {
    @Test void consoleLoginShellIsPublicWhileManagementAndPathAliasesRemainProtected() {
        for (String path : new String[]{"/operator/settings.html", "/operator/operator-bootstrap.js",
                "/operator/operator-ui.css", "/operator/runtime-config.json"}) {
            assertThat(OperatorPublicRoutes.publicStatic(path)).as(path).isTrue();
        }
        for (String path : new String[]{"/api/v1/operator/session", "/api/v1/integrations.html",
                "/operator/../api/connection.js", "/operator/%2e%2e/api.js", "/operator/settings.html;v=1",
                "/operator//settings.html", "/operator/settings", "/operator/secret.txt"}) {
            assertThat(OperatorPublicRoutes.publicStatic(path)).as(path).isFalse();
        }
    }
}
