package com.sitionix.forgeagent.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.domain.port.McpGatewayRuntime;
import jakarta.servlet.FilterChain;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpGatewayRuntimeFilterTest {
    private final UUID connectionId = UUID.randomUUID();
    private final McpGatewayRuntime runtime = mock(McpGatewayRuntime.class);
    private final McpGatewayRuntimeFilter filter = new McpGatewayRuntimeFilter(runtime, Set.of("localhost"));
    private final FilterChain chain = mock(FilterChain.class);

    @Test void validBearerReachesOnlyRuntimeRoute() throws Exception {
        var request = request("/internal/mcp/connections/" + connectionId);
        request.addHeader("Authorization", "Bearer synthetic-runtime-token");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(runtime).authorize("synthetic-runtime-token", connectionId);
        verify(chain).doFilter(request, response);
        assertThat(request.getAttribute(McpGatewayRuntimeFilter.TOKEN_ATTRIBUTE))
                .isEqualTo("synthetic-runtime-token");
    }

    @Test void wrongOriginDuplicateOriginAndMissingBearerStopBeforePolicy() throws Exception {
        var badOrigin = request("/internal/mcp/connections/" + connectionId);
        badOrigin.addHeader("Origin", "https://attacker.example");
        badOrigin.addHeader("Authorization", "Bearer synthetic-runtime-token");
        var response = new MockHttpServletResponse();
        filter.doFilter(badOrigin, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);

        var duplicateOrigin = request("/internal/mcp/connections/" + connectionId);
        duplicateOrigin.addHeader("Origin", "http://localhost:8080");
        duplicateOrigin.addHeader("Origin", "http://localhost:8080");
        filter.doFilter(duplicateOrigin, new MockHttpServletResponse(), chain);

        var missingBearer = request("/internal/mcp/connections/" + connectionId);
        response = new MockHttpServletResponse();
        filter.doFilter(missingBearer, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(runtime);
        verifyNoInteractions(chain);
    }

    @Test void managementAndAlternativePathsAreNotRuntimeOwned() throws Exception {
        var management = request("/api/v1/integrations/mcp/connections/" + connectionId);
        filter.doFilter(management, new MockHttpServletResponse(), chain);
        var alternative = request("/internal/mcp/connections/" + connectionId + "/test");
        filter.doFilter(alternative, new MockHttpServletResponse(), chain);
        verify(chain, times(2)).doFilter(any(), any());
        verifyNoInteractions(runtime);
    }

    private MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("POST", path);
        request.addHeader("Host", "localhost:8080");
        return request;
    }
}
