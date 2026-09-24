package com.sitionix.forgeproxyit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.sitionix.forgeit.wiremock.api.Parameter.equalTo;
import com.sitionix.forgeai.Application;
import com.sitionix.forgeai.api.security.OperatorSessionService;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentMcpClientAdapter;
import com.sitionix.forgeproxyit.infra.*;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import com.sitionix.forgeit.wiremock.api.WireMockPathParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.mock.web.MockHttpSession;

@IntegrationTest(properties={"forge.mcp.enabled=true","forge.remote-access.enabled=true","server.address=127.0.0.1",
    "forge.remote-access.operator-origin=http://127.0.0.1:9099",
    "forge.ai.infrastructure.agent.base-url=${forge-it.wiremock.base-url}",
    "forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}",
    "forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}"})
@ContextConfiguration(classes=Application.class)
@Import({NexusProxyTestManagerImpl.class,NexusCombinedOperatorSessionIT.SessionFixture.class})
@ExtendWith(OutputCaptureExtension.class)
class NexusCombinedOperatorSessionIT {
    static final String ORIGIN="http://127.0.0.1:9099",HOST="127.0.0.1:9099",OPERATOR="o".repeat(43),REMOTE="r".repeat(43);
    static final byte[] SERVICE=new byte[32];
    static final AtomicReference<MockHttpSession> SESSION=new AtomicReference<>();
    static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        Arrays.fill(SERVICE,(byte)4);directory=Files.createTempDirectory("combined-operator-");
        for (String name:List.of("operator","remote","general")) {
            var file=directory.resolve(name);Files.writeString(file,name.equals("operator")?OPERATOR:name.equals("remote")?REMOTE:encoded(SERVICE));
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
            registry.add(name.equals("general")?"forge.mcp.agent-service-credential-file":"forge.remote-access."+(name.equals("operator")?"operator":"service")+"-secret-file",file::toString);
        }
    }
    @TestConfiguration static class SessionFixture {
        @Bean MockMvcBuilderCustomizer currentOperatorSession() {
            return builder -> builder.defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                .with(request -> { var session=SESSION.get();if(session!=null && !session.isInvalid()) request.setSession(session);return request; }));
        }
    }
    @BeforeEach void clearSession() { SESSION.set(null); }
    @AfterAll static void cleanup() throws Exception {
        for(String name:List.of("operator","remote","general"))Files.deleteIfExists(directory.resolve(name));Files.deleteIfExists(directory);
    }
    @Autowired NexusProxyTestManager manager;
    @Autowired org.springframework.context.ApplicationContext context;
    @SpyBean ForgeAgentMcpClientAdapter mcpAdapter;
    record SessionTokens(String csrf) { }
    private SessionTokens login() {
        var result=new AtomicReference<SessionTokens>();
        manager.mockMvc().ping(RemoteAccessEndpoints.login()).header("Host",HOST).header("Origin",ORIGIN)
            .andExpectPath(response -> {
                SESSION.set((MockHttpSession)response.getRequest().getSession(false));
                result.set(new SessionTokens(new ObjectMapper().readTree(response.getResponse().getContentAsString()).path("csrfToken").asText()));
            }).assertDefault();
        return result.get();
    }
    private static String encoded(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    @Test void oneRaLoginAuthorizesBothAudiencesAndNoCustomBackend() {
        assertThat(context.getBeansOfType(OperatorSessionService.class)).isEmpty();
        SessionTokens tokens=login();
        var ra=manager.wiremock().createMapping(RemoteAccessEndpoints.capabilitiesUpstream()).header("Authorization",equalTo("Bearer "+REMOTE)).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.capabilitiesApi()).header("Host",HOST).assertDefault();ra.verify();
        var mutation=manager.wiremock().createMapping(RemoteAccessEndpoints.cancelUpstream()).header("Authorization",equalTo("Bearer "+REMOTE)).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.cancelApi()).header("Host",HOST).header("Origin",ORIGIN).header("X-CSRF-TOKEN",tokens.csrf()).assertDefault();mutation.verify();
        var mcp=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.createMcpConnection()).header("Authorization",equalTo("Bearer "+encoded(SERVICE))).createDefault();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.createValidMcpConnection()).header("Host",HOST).header("Origin",ORIGIN).header("X-CSRF-TOKEN",tokens.csrf()).assertDefault();mcp.verify();
    }
    @Test void denialBeforeUpstreamForMissingSessionWrongOriginCsrfAndExpiredSession() {
        clearInvocations(mcpAdapter);
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.listMcpConnections(401)).header("Host",HOST).assertDefault();
        var tokens=login();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.createMcpConnection(403)).header("Host",HOST).header("Origin",ORIGIN).assertDefault();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.listMcpConnections(403)).header("Host",HOST).header("Origin","http://evil.test").assertDefault();
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.listMcpConnections(403)).header("Host",HOST).header("X-Forwarded-Host",HOST).assertDefault();
        SESSION.get().setAttribute(com.sitionix.forgeai.api.remoteaccess.RemoteAccessOperatorAuthentication.AUTHENTICATED_AT,0L);
        manager.mockMvc().ping(NexusAgentMockMvcEndpoints.listMcpConnections(401)).header("Host",HOST).assertDefault();
        verifyNoInteractions(mcpAdapter);
    }
  @Test
  void mcpDetailUpdatesEnableReencryptAndDeleteUseTypedGuardedRoutes(CapturedOutput output) throws Exception {
    SessionTokens tokens=login();
    UUID id=UUID.fromString("11111111-1111-4111-8111-111111111111");
    var path=PathParams.create().add("id",id);
    var wirePath=WireMockPathParams.create().add("id",equalTo(id.toString()));
    String serviceBearer="Bearer " + encoded(SERVICE);

    var get=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.getMcpConnection())
        .pathPattern(wirePath).header("Authorization",equalTo(serviceBearer)).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.getMcpConnection(200))
        .withPathParameters(path).header("Host",HOST)
        .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
            .contains("credentialConfigured","STREAMABLE_HTTP").doesNotContain("nexus-canary-secret"))
        .assertDefault();
    get.verify();

    String[][] changes={{"mcp-keep-request.json","agent-mcp-keep-request.json"},
        {"mcp-replace-request.json","agent-mcp-replace-request.json"},
        {"mcp-remove-request.json","agent-mcp-remove-request.json"}};
    for(String[] change:changes){
      var upstream=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.updateMcpConnection(change[1]))
          .pathPattern(wirePath).header("Authorization",equalTo(serviceBearer)).createDefault();
      manager.mockMvc().ping(NexusAgentMockMvcEndpoints.updateMcpConnection(change[0],200))
          .withPathParameters(path).header("Host",HOST).header("Origin",ORIGIN)
          .header("X-CSRF-TOKEN",tokens.csrf())
          .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
              .contains("updated").doesNotContain("replacement-canary"))
          .assertDefault();
      upstream.verify();
    }

    var enable=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.enableMcpConnection())
        .pathPattern(wirePath).header("Authorization",equalTo(serviceBearer)).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.enableMcpConnection(200))
        .withPathParameters(path).header("Host",HOST).header("Origin",ORIGIN)
        .header("X-CSRF-TOKEN",tokens.csrf())
        .andExpectPath(result -> assertThat(new ObjectMapper().readTree(result.getResponse().getContentAsString())
            .path("enabled").asBoolean()).isTrue())
        .assertDefault();
    enable.verify();

    var rotate=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.reencryptMcpConnection())
        .pathPattern(wirePath).header("Authorization",equalTo(serviceBearer)).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.reencryptMcpConnection(204))
        .withPathParameters(path).header("Host",HOST).header("Origin",ORIGIN)
        .header("X-CSRF-TOKEN",tokens.csrf()).assertDefault();
    rotate.verify();

    var delete=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.deleteMcpConnection())
        .pathPattern(wirePath).header("Authorization",equalTo(serviceBearer)).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.deleteMcpConnection(204))
        .withPathParameters(path).header("Host",HOST).header("Origin",ORIGIN)
        .header("X-CSRF-TOKEN",tokens.csrf()).assertDefault();
    delete.verify();
    assertThat(output.getAll()).doesNotContain("replacement-canary");
  }

}
