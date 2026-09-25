package com.sitionix.forgeai.api.remoteaccess;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import com.sitionix.forgeai.domain.remoteaccess.*;
import java.net.URI;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.mapstruct.factory.Mappers;
class RemoteAccessOperatorSecurityTest {
    static final String BASE="/api/v1/infrastructure/agents/remote-access", SECRET="a".repeat(43), ORIGIN="http://127.0.0.1:9099";
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    @Configuration @EnableWebMvc
    @Import({RemoteAccessSecurityConfiguration.class,RemoteAccessOperatorController.class,RemoteAccessProxyController.class,RemoteAccessProxyErrors.class})
    static class Config {
        @Bean RemoteAccessOperatorAuthentication authentication() { return new RemoteAccessOperatorAuthentication(SECRET.getBytes(),URI.create(ORIGIN),Clock.systemUTC()); }
        @Bean RemoteAccessOperations operations() { return mock(RemoteAccessOperations.class); }
        @Bean RemoteAccessProxyMapper mapper() { return Mappers.getMapper(RemoteAccessProxyMapper.class); }
    }
    @BeforeEach void setup() {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getSystemProperties().put("forge.remote-access.enabled","true");
        context.register(Config.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close() { context.close();System.clearProperty("forge.remote-access.enabled"); }
    @Test void anonymousAndHostileOriginNeverReachUpstream() throws Exception {
        mvc.perform(get(BASE+"/sessions").header("Host","127.0.0.1:9099")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE+"/operator/login").header("Host","127.0.0.1:9099").header("Origin","http://evil.test")
            .contentType("application/json").content("{\"secret\":\""+SECRET+"\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(RemoteAccessOperations.class));
    }
    @Test void loginSessionCsrfAndLogoutProtectMutation() throws Exception {
        var login=mvc.perform(post(BASE+"/operator/login").header("Host","127.0.0.1:9099").header("Origin",ORIGIN)
            .contentType("application/json").content("{\"secret\":\""+SECRET+"\"}")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andReturn();
        var session=(MockHttpSession)login.getRequest().getSession(false);
        String csrf=new com.fasterxml.jackson.databind.ObjectMapper().readValue(login.getResponse().getContentAsString(),RemoteAccessOperatorController.OperatorSession.class).csrfToken();
        mvc.perform(delete(BASE+"/invitations/"+java.util.UUID.randomUUID()).session(session).header("Host","127.0.0.1:9099").header("Origin",ORIGIN)).andExpect(status().isForbidden());
        var id=java.util.UUID.randomUUID();
        mvc.perform(delete(BASE+"/invitations/"+id).session(session).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",csrf)).andExpect(status().isNoContent());
        verify(context.getBean(RemoteAccessOperations.class)).cancel(id);
        mvc.perform(post(BASE+"/operator/logout").session(session).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",csrf)).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }
    @Test void localBrowserOpensSessionWithoutAnExtraCredentialPrompt() throws Exception {
        var opened=mvc.perform(get(BASE+"/operator/session").header("Host","127.0.0.1:9099")
                .header("Sec-Fetch-Site","same-origin"))
            .andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)opened.getRequest().getSession(false);
        assertThat(session).isNotNull();
        String csrf=new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                opened.getResponse().getContentAsString(),RemoteAccessOperatorController.OperatorSession.class).csrfToken();
        assertThat(csrf).isNotBlank();
        var id=java.util.UUID.randomUUID();
        mvc.perform(delete(BASE+"/invitations/"+id).session(session).header("Host","127.0.0.1:9099")
                .header("Origin",ORIGIN).header("X-CSRF-TOKEN",csrf)).andExpect(status().isNoContent());
        verify(context.getBean(RemoteAccessOperations.class)).cancel(id);
    }
}
