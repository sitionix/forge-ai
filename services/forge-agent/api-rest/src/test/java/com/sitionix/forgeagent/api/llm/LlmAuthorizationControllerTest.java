package com.sitionix.forgeagent.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sitionix.forgeagent.application.llm.LlmAuthorizationService;
import com.sitionix.forgeagent.domain.port.*;
import java.time.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class LlmAuthorizationControllerTest {
    static final String BASE="/api/v1/integrations/llm";
    static LlmAuthorizationGateway gateway;
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    @Configuration @EnableWebMvc @ComponentScan("com.sitionix.forgeagent.api.llm")
    static class Config {
        @Bean com.fasterxml.jackson.databind.ObjectMapper mapper(){return new com.fasterxml.jackson.databind.ObjectMapper();}
        @Bean ForgeCodexOperationsPort codexOperations(){return mock(ForgeCodexOperationsPort.class);}
        @Bean LlmAuthorizationPort authorization(){return new LlmAuthorizationService(gateway,Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"),ZoneOffset.UTC));}
    }
    @BeforeEach void setup(){gateway=mock(LlmAuthorizationGateway.class);context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBeansOfType(Filter.class).values().toArray(Filter[]::new)).build();}
    @AfterEach void close(){context.close();}
    @Test void directBrowserAndSimpleFormCannotMutateAgent() throws Exception {
        mvc.perform(post(BASE+"/codex/logout").header("Origin","http://evil.example").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post(BASE+"/codex/login").header("Sec-Fetch-Site","same-origin").contentType("application/json").content("{\"browserBinding\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE+"/codex/logout").contentType("application/x-www-form-urlencoded").content("logout=true")).andExpect(status().isForbidden());
        verifyNoInteractions(gateway);
    }
    @Test void typedLifecyclePreservesOwnerAndDuplicateAttemptAndSafeErrors() throws Exception {
        var session=mock(LlmAuthorizationGateway.Session.class);when(gateway.openSession()).thenReturn(session);when(session.healthy()).thenReturn(true);
        when(session.readAccount(false)).thenReturn(new LlmAuthorizationGateway.Account(false,null,null));when(session.drainEvents()).thenReturn(java.util.List.of());
        when(session.startLogin()).thenReturn(new LlmAuthorizationGateway.Login("provider-id","https://auth.openai.com/authorize?state=synthetic"));
        mvc.perform(get(BASE+"/providers")).andExpect(status().isOk()).andExpect(jsonPath("$[0].generation").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
        var start=mvc.perform(post(BASE+"/codex/login").contentType("application/json").content("{\"browserBinding\":\"owner-binding\"}"))
                .andExpect(status().isOk()).andReturn();
        var id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(start.getResponse().getContentAsString()).get("loginId").asText();
        mvc.perform(post(BASE+"/codex/login").contentType("application/json").content("{\"browserBinding\":\"owner-binding\"}" )).andExpect(jsonPath("$.loginId").value(id));
        mvc.perform(post(BASE+"/codex/login").contentType("application/json").content("{\"browserBinding\":\"other-binding\"}" )).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOGIN_IN_PROGRESS"));
        mvc.perform(get(BASE+"/codex/logins/"+id).header("X-Forge-Browser-Binding","other-binding")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOGIN_NOT_FOUND")).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get(BASE+"/codex/logins/00000000-0000-0000-0000-000000000000").header("X-Forge-Browser-Binding","owner-binding")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOGIN_NOT_FOUND"));
        mvc.perform(delete(BASE+"/codex/logins/"+id).contentType("application/json").content("{\"browserBinding\":\"owner-binding\"}" )).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.authUrl").isEmpty());
        mvc.perform(post(BASE+"/codex/logout").contentType("application/json").content("{}" )).andExpect(status().isOk()).andExpect(jsonPath("$.authState").value("SIGNED_OUT"));
        assertThat(start.getResponse().getContentAsString()).doesNotContain("browserBinding","provider-id","generation");
    }
    @Test void malformedAndMissingBindingsAreSafeUncachedErrors() throws Exception {
        mvc.perform(post(BASE+"/codex/login").contentType("application/json").content("{}" )).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BROWSER_BINDING"));
        mvc.perform(get(BASE+"/codex/logins/not-uuid").header("X-Forge-Browser-Binding","binding-canary" )).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post(BASE+"/codex/login").contentType("application/json").content("{\"browserBinding\":\"binding\",\"credential\":\"secret-canary\"}" )).andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("canary"))));
        verifyNoInteractions(gateway);
    }
    @Test void matrixPathCannotBypassDirectBrowserGuard() throws Exception {
        mvc.perform(post(BASE+";v=1/codex/logout").header("Origin","http://evil.example").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(gateway);
    }
}
