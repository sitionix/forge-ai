package com.sitionix.forgeai.api.llm;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.time.Duration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class LlmAuthorizationBrowserGuardTest {
    private MockHttpServletRequest local(String path){var request=new MockHttpServletRequest("GET",path);request.setServerName("127.0.0.1");request.setServerPort(9099);request.addHeader("Host","127.0.0.1:9099");return request;}
    @Test void expiredBindingCannotReadAttemptAndProvenReloadReissuesIt(){
        var guard=new LlmAuthorizationBrowserGuard(new LlmAuthorizationBrowserProperties(URI.create("http://127.0.0.1:9099"),Duration.ofSeconds(1)));
        var first=local(ForgeAiLlmAuthorizationController.PREFIX+"/providers");first.addHeader("Sec-Fetch-Site","same-origin");var response=new MockHttpServletResponse();guard.require(first,response,true);
        var value=(String)first.getAttribute(LlmAuthorizationBrowserGuard.BINDING_ATTRIBUTE);
        var read=local(ForgeAiLlmAuthorizationController.PREFIX+"/codex/logins/00000000-0000-0000-0000-000000000000");read.setCookies(new Cookie("ForgeLlmBrowser",value));
        guard.require(read,new MockHttpServletResponse(),false);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThatThrownBy(()->guard.require(read,new MockHttpServletResponse(),false)).isInstanceOf(LlmAuthorizationBrowserGuard.Denied.class));
        var reload=local(ForgeAiLlmAuthorizationController.PREFIX+"/providers");reload.addHeader("Sec-Fetch-Site","same-origin");reload.setCookies(new Cookie("ForgeLlmBrowser",value));var renewed=new MockHttpServletResponse();guard.require(reload,renewed,true);
        assertThat(reload.getAttribute(LlmAuthorizationBrowserGuard.BINDING_ATTRIBUTE)).isNotEqualTo(value);assertThat(renewed.getHeader("Set-Cookie")).contains("HttpOnly","SameSite=Lax");
    }
}
