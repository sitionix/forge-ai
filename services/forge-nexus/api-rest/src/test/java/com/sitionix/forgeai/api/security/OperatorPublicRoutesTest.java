package com.sitionix.forgeai.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class OperatorPublicRoutesTest {
    @Test void usesCurrentDispatchTargetWithoutContextPath() {
        var request = new MockHttpServletRequest("GET", "/fgaisox/api/v1/infrastructure/agents/remote-access/capabilities");
        request.setContextPath("/fgaisox");
        assertThat(OperatorPublicRoutes.path(request)).isEqualTo("/api/v1/infrastructure/agents/remote-access/capabilities");
        request.setDispatcherType(DispatcherType.INCLUDE);
        request.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, "/fgaisox/api/v1/infrastructure/agents/remote-access/operator/session");
        assertThat(OperatorPublicRoutes.path(request)).isEqualTo("/api/v1/infrastructure/agents/remote-access/operator/session");
    }
}
