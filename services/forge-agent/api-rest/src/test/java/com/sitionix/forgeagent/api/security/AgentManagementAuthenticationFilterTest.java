package com.sitionix.forgeagent.api.security;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;

class AgentManagementAuthenticationFilterTest {
    @TempDir Path directory;

    @Test void requiresExactServiceBearerBeforeExistingControlAndEncodedPaths() throws Exception {
        byte[] secret = new byte[32]; Arrays.fill(secret,(byte)21);
        Path file = directory.resolve("service");
        Files.writeString(file,Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        var filter = new AgentManagementAuthenticationFilter(new ProtectedCredentialFile(file));
        for (String path : List.of("/api/v1/projects","/api%2fv1/projects","/api/v1;ignored/projects","/api/v1/projects/x/logs/stream")) {
            var request = new MockHttpServletRequest("GET",path); request.setServletPath(path);
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request,response,chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
        }
        var request = new MockHttpServletRequest("GET","/api/v1/projects"); request.setServletPath("/api/v1/projects");
        request.addHeader("Authorization","Bearer "+Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        var chain = new MockFilterChain();
        filter.doFilter(request,new MockHttpServletResponse(),chain);
        assertThat(chain.getRequest()).isSameAs(request);
        var shell = new MockHttpServletRequest("GET","/index.html"); shell.setServletPath("/index.html");
        var shellChain = new MockFilterChain();
        filter.doFilter(shell,new MockHttpServletResponse(),shellChain);
        assertThat(shellChain.getRequest()).isSameAs(shell);
    }

    @Test void wrongRuntimeOrBootstrapBearerCannotReachApplication() throws Exception {
        byte[] secret = new byte[32]; Arrays.fill(secret,(byte)3);
        Path file = directory.resolve("service"); Files.writeString(file,Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        var filter = new AgentManagementAuthenticationFilter(new ProtectedCredentialFile(file));
        for (String candidate : List.of("Bearer "+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]),
                "Bearer "+Base64.getUrlEncoder().withoutPadding().encodeToString(secret)+"x","Basic abc")) {
            var request = new MockHttpServletRequest("POST","/api/v1/projects"); request.setServletPath("/api/v1/projects");
            request.addHeader("Authorization",candidate);
            var chain = new MockFilterChain(); var response = new MockHttpServletResponse();
            filter.doFilter(request,response,chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }
}
