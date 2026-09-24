package com.sitionix.forgeai.api.security;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.*;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class OperatorSessionControllerTest {
    @TempDir Path directory;
    @Test void acceptedHttpsSchemeCaseAlwaysSetsSecureWhileLoopbackHttpDoesNot() throws Exception {
        byte[] bytes=new byte[32]; Arrays.fill(bytes,(byte)23);
        String bootstrap=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Path path=directory.resolve("mixed-case-bootstrap"); Files.writeString(path,bootstrap);
        Files.setPosixFilePermissions(path,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        var sessions=new OperatorSessionService(new ProtectedCredentialFile(path),Clock.systemUTC(),Duration.ofMinutes(10),10);
        var request=new MockHttpServletRequest(); request.setContextPath("/fgaisox");
        for (String origin : List.of("https://operator.example", "HTTPS://operator.example", "HtTpS://operator.example")) {
            var controller=new OperatorSessionController(sessions,URI.create(origin));
            var cookie=controller.login(new OperatorSessionController.LoginRequest(bootstrap),request)
                    .getHeaders().getFirst("Set-Cookie");
            assertThat(cookie).contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/fgaisox");
        }
        var local=new OperatorSessionController(sessions,URI.create("http://127.0.0.1:9099"));
        var localCookie=local.login(new OperatorSessionController.LoginRequest(bootstrap),request)
                .getHeaders().getFirst("Set-Cookie");
        assertThat(localCookie).contains("HttpOnly", "SameSite=Strict").doesNotContain("Secure");
    }
    @Test void loginSessionAndLogoutUseHostOnlyNoStoreCookieAndRedactedBody() throws Exception {
        byte[] bytes=new byte[32]; Arrays.fill(bytes,(byte)14);
        String bootstrap=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Path path=directory.resolve("bootstrap"); Files.writeString(path,bootstrap);
        Files.setPosixFilePermissions(path,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        var service=new OperatorSessionService(new ProtectedCredentialFile(path),Clock.systemUTC(),Duration.ofMinutes(10),10);
        var controller=new OperatorSessionController(service,URI.create("https://example.org:443"));
        var body=new ObjectMapper().readValue("{\"bootstrapSecret\":\""+bootstrap+"\"}",OperatorSessionController.LoginRequest.class);
        assertThat(body.toString()).doesNotContain(bootstrap);
        assertThatThrownBy(() -> new ObjectMapper().writeValueAsString(body))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class)
                .hasMessageNotContaining(bootstrap);
        var request=new MockHttpServletRequest(); request.setContextPath("/fgaisox");
        var login=controller.login(body,request);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        assertThat(login.getHeaders().getFirst("Set-Cookie"))
                .contains("FG_SESSION=","HttpOnly","Secure","SameSite=Strict","Path=/fgaisox")
                .doesNotContain("Domain=");
        assertThat(login.getHeaders().getCacheControl()).isEqualTo("no-store");
        String id=login.getHeaders().getFirst("Set-Cookie").split("[=;]")[1];
        request.setCookies(new Cookie("FG_SESSION",id));
        assertThat(controller.session(request).getBody().csrfToken()).isNotBlank();
        var logout=controller.logout(request);
        assertThat(logout.getStatusCode().value()).isEqualTo(204);
        assertThat(service.find(id)).isEmpty();
        assertThat(logout.getHeaders().getFirst("Set-Cookie")).contains("Max-Age=0");
    }
}
