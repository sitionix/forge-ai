package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway;
import com.sitionix.forgeagent.it.infra.AgentManagementFixture;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@IntegrationTest(properties="logging.level.org.springframework.web=TRACE")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class AgentLlmAuthorizationIT extends AgentManagementFixture {
    static final String BASE="/api/v1/integrations/llm";
    @org.springframework.test.context.DynamicPropertySource
    static void isolatedAuthorizationProfile(org.springframework.test.context.DynamicPropertyRegistry registry) throws java.io.IOException {
        var directory=java.nio.file.Files.createTempDirectory("forge-authorization-it-",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        registry.add("forge.agent.codex.app-server.runtime-cwd",directory::toString);
    }
    @Autowired WebApplicationContext context;
    @Autowired com.sitionix.forgeagent.it.infra.ForgeAgentTestManager forgeIt;
    @MockBean LlmAuthorizationGateway gateway;
    MockMvc mvc;
    @BeforeEach void setup(){mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBeansOfType(jakarta.servlet.Filter.class).values().toArray(jakarta.servlet.Filter[]::new)).build();}
    @Test void browserCannotInvokeDirectAgentLifecycle() throws Exception {
        mvc.perform(post(BASE+"/codex/logout").header("Origin","http://evil.example").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post(BASE+"/codex/logout").contentType("application/x-www-form-urlencoded").content("logout=true"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(gateway);
    }
    @Test void lifecycleUsesRealServiceOwnerChecksAndNeverPublishesGeneration(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var session=mock(LlmAuthorizationGateway.Session.class);
        when(gateway.openSession()).thenReturn(session);when(session.healthy()).thenReturn(true);
        when(session.readAccount(false)).thenReturn(new LlmAuthorizationGateway.Account(false,null,null));
        when(session.startLogin()).thenReturn(new LlmAuthorizationGateway.Login("provider-id","https://auth.openai.com/authorize?state=synthetic"));
        when(session.drainEvents()).thenReturn(java.util.List.of());
        mvc.perform(get(BASE+"/providers")).andExpect(status().isOk()).andExpect(jsonPath("$[0].generation").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
        var start=mvc.perform(post(BASE+"/codex/login").contentType("application/json").content("{\"browserBinding\":\"owner-binding\"}"))
                .andExpect(status().isOk()).andReturn();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(start.getResponse().getContentAsString());var id=json.get("loginId").asText();
        mvc.perform(post(BASE+"/codex/login").contentType("application/json").content("{\"browserBinding\":\"owner-binding\"}" )).andExpect(jsonPath("$.loginId").value(id));
        mvc.perform(post(BASE+"/codex/login").contentType("application/json").content("{\"browserBinding\":\"other-binding\"}" )).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOGIN_IN_PROGRESS"));
        mvc.perform(get(BASE+"/codex/logins/"+id).header("X-Forge-Browser-Binding","other-binding")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOGIN_NOT_FOUND"));
        mvc.perform(get(BASE+"/codex/logins/00000000-0000-0000-0000-000000000000").header("X-Forge-Browser-Binding","owner-binding")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOGIN_NOT_FOUND"));
        mvc.perform(get(BASE+"/codex/logins/"+id).header("X-Forge-Browser-Binding","owner-binding")).andExpect(status().isOk());
        mvc.perform(delete(BASE+"/codex/logins/"+id).contentType("application/json").content("{\"browserBinding\":\"owner-binding\"}" )).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.authUrl").isEmpty());
        mvc.perform(post(BASE+"/codex/logout").contentType("application/json").content("{}" )).andExpect(status().isOk()).andExpect(jsonPath("$.authState").value("SIGNED_OUT"));
        assertThat(start.getResponse().getContentAsString()).doesNotContain("browserBinding","provider-id","generation");
        assertThat(output.getAll()).doesNotContain("owner-binding","other-binding","https://auth.openai.com/authorize?state=synthetic");
    }
}
