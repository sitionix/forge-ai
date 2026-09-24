package com.sitionix.forgeai.api.security;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.*;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;

class OperatorSessionServiceTest {
    @TempDir Path directory;

    @Test void loginIssuesFreshSessionAndExpiryLogoutInvalidateIt() throws Exception {
        String bootstrap = token((byte)17);
        var clock = new MutableClock();
        var service = new OperatorSessionService(new ProtectedCredentialFile(file(bootstrap)),clock,Duration.ofMinutes(15),4);
        assertThatThrownBy(() -> service.login(token((byte)3))).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(bootstrap);
        var first = service.login(bootstrap);
        var second = service.login(bootstrap);
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.csrf()).isNotEqualTo(second.csrf());
        assertThat(first.toString()).doesNotContain(first.id(),first.csrf());
        assertThat(service.find(first.id())).isPresent();
        assertThat(service.csrfMatches(first,"wrong")).isFalse();
        assertThat(service.csrfMatches(first,first.csrf())).isTrue();
        service.logout(first.id());
        assertThat(service.find(first.id())).isEmpty();
        clock.advance(Duration.ofMinutes(16));
        assertThat(service.find(second.id())).isEmpty();
    }

    @Test void guardRejectsHostOriginCsrfAndEncodedPathsBeforeApplication() throws Exception {
        String bootstrap = token((byte)17);
        var service = new OperatorSessionService(new ProtectedCredentialFile(file(bootstrap)),new MutableClock(),Duration.ofMinutes(15),4);
        var session = service.login(bootstrap);
        var guard = new OperatorManagementAuthenticationFilter(service,URI.create("http://127.0.0.1:9099"));
        for (String path : List.of("/fgaisox/api/v1/projects","/fgaisox/api%2fv1/projects","/fgaisox/api/v1;bad/projects","/fgaisox/api/v1/projects/x/logs/stream")) {
            var request = request("GET",path); var chain = new MockFilterChain(); var response = new MockHttpServletResponse();
            guard.doFilter(request,response,chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
        var wrongHost = request("GET","/fgaisox/api/v1/projects"); wrongHost.addHeader("Host","evil.example");
        wrongHost.setCookies(new Cookie("FG_SESSION",session.id()));
        var wrongResponse = new MockHttpServletResponse(); var wrongChain = new MockFilterChain();
        guard.doFilter(wrongHost,wrongResponse,wrongChain);
        assertThat(wrongResponse.getStatus()).isEqualTo(403); assertThat(wrongChain.getRequest()).isNull();
        var mutation = request("POST","/fgaisox/api/v1/projects"); mutation.setCookies(new Cookie("FG_SESSION",session.id()));
        mutation.addHeader("Origin","http://127.0.0.1:9099");
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        guard.doFilter(mutation,response,chain);
        assertThat(response.getStatus()).isEqualTo(403); assertThat(chain.getRequest()).isNull();
        mutation.addHeader("X-Forge-CSRF",session.csrf());
        response = new MockHttpServletResponse(); chain = new MockFilterChain();
        guard.doFilter(mutation,response,chain);
        assertThat(chain.getRequest()).isSameAs(mutation);
    }

    @Test void staticShellIsAvailableWhileAmbiguousEncodedApiRemainsGuarded() throws Exception {
        var service = new OperatorSessionService(new ProtectedCredentialFile(file(token((byte)17))),
                new MutableClock(),Duration.ofMinutes(15),4);
        var guard = new OperatorManagementAuthenticationFilter(service,URI.create("http://127.0.0.1:9099"));
        var shell = request("GET","/fgaisox/index.html");
        shell.removeHeader("Host");
        var shellChain = new MockFilterChain();
        guard.doFilter(shell,new MockHttpServletResponse(),shellChain);
        assertThat(shellChain.getRequest()).isSameAs(shell);
        var encoded = request("GET","/fgaisox/%61pi/v1/projects");
        var encodedChain = new MockFilterChain();
        var encodedResponse = new MockHttpServletResponse();
        guard.doFilter(encoded,encodedResponse,encodedChain);
        assertThat(encodedResponse.getStatus()).isEqualTo(401);
        assertThat(encodedChain.getRequest()).isNull();
    }

    @Test void implicitAndExplicitDefaultHttpsPortUseCanonicalBrowserHostAndOrigin() throws Exception {
        var service = new OperatorSessionService(new ProtectedCredentialFile(file(token((byte)17))),
                new MutableClock(),Duration.ofMinutes(15),4);
        for (String configured : List.of("https://operator.example", "https://operator.example:443")) {
            var guard = new OperatorManagementAuthenticationFilter(service,URI.create(configured));
            var login = new MockHttpServletRequest("POST","/api/v1/operator/session");
            login.setServletPath("/api/v1/operator/session");
            login.addHeader("Host","operator.example");
            login.addHeader("Origin","https://operator.example");
            var chain = new MockFilterChain();
            guard.doFilter(login,new MockHttpServletResponse(),chain);
            assertThat(chain.getRequest()).isSameAs(login);
            var controller = new OperatorSessionController(service,URI.create(configured));
            assertThat(controller.login(new OperatorSessionController.LoginRequest(token((byte)17)),login)
                    .getHeaders().getFirst("Set-Cookie")).contains("Secure","HttpOnly","SameSite=Strict");
        }
    }

    private MockHttpServletRequest request(String method,String path) {
        var request = new MockHttpServletRequest(method,path);
        request.setContextPath("/fgaisox"); request.setServletPath(path.substring("/fgaisox".length()));
        request.addHeader("Host","127.0.0.1:9099");
        return request;
    }
    private Path file(String value) throws Exception {
        Path path = directory.resolve("bootstrap"); Files.writeString(path,value);
        Files.setPosixFilePermissions(path,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        return path;
    }
    private static String token(byte value) { byte[] bytes=new byte[32]; Arrays.fill(bytes,value); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static final class MutableClock extends Clock {
        private Instant instant=Instant.parse("2026-09-23T10:00:00Z");
        void advance(Duration duration) { instant=instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
