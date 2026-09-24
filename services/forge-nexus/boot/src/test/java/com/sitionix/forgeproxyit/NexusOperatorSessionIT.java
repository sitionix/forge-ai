package com.sitionix.forgeproxyit;

import static com.sitionix.forgeit.wiremock.api.Parameter.equalTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeai.Application;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentClientAdapter;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentMcpClientAdapter;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import com.sitionix.forgeit.wiremock.api.WireMockPathParams;
import com.sitionix.forgeproxyit.infra.ForgeAgentWireMockEndpoints;
import com.sitionix.forgeproxyit.infra.NexusAgentMockMvcEndpoints;
import com.sitionix.forgeproxyit.infra.NexusOperatorMockMvcEndpoints;
import com.sitionix.forgeproxyit.infra.NexusProxyTestManager;
import com.sitionix.forgeproxyit.infra.NexusProxyTestManagerImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@IntegrationTest(properties = {
    "forge.mcp.enabled=true",
    "forge.mcp.operator-origin=http://127.0.0.1:9099",
    "forge.ai.infrastructure.agent.base-url=${forge-it.wiremock.base-url}",
    "forge.ai.infrastructure.agent.connect-timeout=5s",
    "forge.ai.infrastructure.agent.read-timeout=5s",
    "forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}",
    "forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}"
})
@ContextConfiguration(classes = Application.class)
@Import(NexusProxyTestManagerImpl.class)
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(OutputCaptureExtension.class)
class NexusOperatorSessionIT {
  private static final String ORIGIN = "http://127.0.0.1:9099";
  private static final String HOST = "127.0.0.1:9099";
  private static final UUID WORKFLOW_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final byte[] BOOTSTRAP = new byte[32];
  private static final byte[] SERVICE = new byte[32];
  private static final Path DIRECTORY;
  private static final Path BOOTSTRAP_FILE;
  private static final Path SERVICE_FILE;
  static {
    try {
      Arrays.fill(BOOTSTRAP, (byte) 3);
      Arrays.fill(SERVICE, (byte) 4);
      DIRECTORY = Files.createTempDirectory("nexus-operator-it");
      BOOTSTRAP_FILE = credential("bootstrap", BOOTSTRAP);
      SERVICE_FILE = credential("service", SERVICE);
    } catch (Exception exception) {
      throw new IllegalStateException("Synthetic credential setup failed");
    }
  }

  @DynamicPropertySource
  static void credentials(DynamicPropertyRegistry registry) {
    registry.add("forge.mcp.bootstrap-credential-file", BOOTSTRAP_FILE::toString);
    registry.add("forge.mcp.agent-service-credential-file", SERVICE_FILE::toString);
  }

  @AfterAll
  static void cleanup() throws Exception {
    Files.deleteIfExists(BOOTSTRAP_FILE);
    Files.deleteIfExists(SERVICE_FILE);
    Files.deleteIfExists(DIRECTORY);
  }

  @Autowired NexusProxyTestManager manager;
  @SpyBean ForgeAgentClientAdapter agentAdapter;
  @SpyBean ForgeAgentMcpClientAdapter mcpAdapter;

  @Test
  void mcpRouteRejectsAbsentSessionAndUnsafeInputLocally(CapturedOutput output) throws Exception {
    clearInvocations(mcpAdapter);
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.listMcpConnections(401))
        .header("Host",HOST).assertDefault();
    verifyNoInteractions(mcpAdapter);
    SessionTokens tokens=login();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.invalidMcpId())
        .header("Host",HOST).cookie("FG_SESSION",tokens.id()).assertDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.createMcpConnection(400))
        .header("Host",HOST).header("Origin",ORIGIN).header("X-Forge-CSRF",tokens.csrf())
        .cookie("FG_SESSION",tokens.id())
        .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
            .doesNotContain("nexus-canary-secret"))
        .assertDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.createMcpConnection(400,"mcp-malformed-credential-request.json"))
        .header("Host",HOST).header("Origin",ORIGIN).header("X-Forge-CSRF",tokens.csrf())
        .cookie("FG_SESSION",tokens.id())
        .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
            .doesNotContain("nexus-malformed-canary"))
        .assertDefault();
    for(String fixture:new String[]{"mcp-unknown-transport-request.json","mcp-tool-approval-request.json"}){
      manager.mockMvc().ping(NexusAgentMockMvcEndpoints.createMcpConnection(400,fixture))
          .header("Host",HOST).header("Origin",ORIGIN).header("X-Forge-CSRF",tokens.csrf())
          .cookie("FG_SESSION",tokens.id()).assertDefault();
    }
    verifyNoInteractions(mcpAdapter);
    assertThat(output.getAll()).doesNotContain("nexus-canary-secret","nexus-malformed-canary");
  }

  @Test
  void emptyCredentialEnvelopeIsRejectedBeforeAgentMutation() throws Exception {
    clearInvocations(mcpAdapter);
    SessionTokens tokens=login();
    for (String fixture : new String[]{"mcp-empty-keep-request.json","mcp-null-remove-request.json"}) {
      manager.mockMvc().ping(NexusAgentMockMvcEndpoints.invalidMcpUpdate(fixture))
          .withPathParameters(PathParams.create().add("id",WORKFLOW_ID))
          .header("Host",HOST).header("Origin",ORIGIN).header("X-Forge-CSRF",tokens.csrf())
          .cookie("FG_SESSION",tokens.id()).assertDefault();
    }
    verifyNoInteractions(mcpAdapter);
  }

  @Test
  void authenticatedMcpMetadataUsesServiceBearer() throws Exception {
    SessionTokens tokens=login();
    final var upstream=manager.wiremock()
        .createMapping(ForgeAgentWireMockEndpoints.listMcpConnections())
        .header("Authorization",equalTo("Bearer " + encoded(SERVICE)))
        .createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.listMcpConnections(200))
        .header("Host",HOST).header("Authorization","Bearer caller")
        .cookie("FG_SESSION",tokens.id()).assertDefault();
    upstream.verify();
  }

  @Test
  void authenticatedMcpCreateForwardsCredentialOnlyInAgentBody(CapturedOutput output) throws Exception {
    SessionTokens tokens=login();
    final var upstream=manager.wiremock()
        .createMapping(ForgeAgentWireMockEndpoints.createMcpConnection())
        .header("Authorization",equalTo("Bearer " + encoded(SERVICE)))
        .createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.createValidMcpConnection())
        .header("Host",HOST).header("Origin",ORIGIN).header("X-Forge-CSRF",tokens.csrf())
        .header("Authorization","Bearer caller")
        .cookie("FG_SESSION",tokens.id())
        .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
            .contains("credentialConfigured").doesNotContain("nexus-canary-secret","ciphertext"))
        .assertDefault();
    upstream.verify();
    assertThat(output.getAll()).doesNotContain("nexus-canary-secret");
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
        .withPathParameters(path).header("Host",HOST).cookie("FG_SESSION",tokens.id())
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
          .header("X-Forge-CSRF",tokens.csrf()).cookie("FG_SESSION",tokens.id())
          .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
              .contains("updated").doesNotContain("replacement-canary"))
          .assertDefault();
      upstream.verify();
    }

    var enable=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.enableMcpConnection())
        .pathPattern(wirePath).header("Authorization",equalTo(serviceBearer)).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.enableMcpConnection(200))
        .withPathParameters(path).header("Host",HOST).header("Origin",ORIGIN)
        .header("X-Forge-CSRF",tokens.csrf()).cookie("FG_SESSION",tokens.id())
        .andExpectPath(result -> assertThat(new ObjectMapper().readTree(result.getResponse().getContentAsString())
            .path("enabled").asBoolean()).isTrue())
        .assertDefault();
    enable.verify();

    var rotate=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.reencryptMcpConnection())
        .pathPattern(wirePath).header("Authorization",equalTo(serviceBearer)).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.reencryptMcpConnection(204))
        .withPathParameters(path).header("Host",HOST).header("Origin",ORIGIN)
        .header("X-Forge-CSRF",tokens.csrf()).cookie("FG_SESSION",tokens.id()).assertDefault();
    rotate.verify();

    var delete=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.deleteMcpConnection())
        .pathPattern(wirePath).header("Authorization",equalTo(serviceBearer)).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.deleteMcpConnection(204))
        .withPathParameters(path).header("Host",HOST).header("Origin",ORIGIN)
        .header("X-Forge-CSRF",tokens.csrf()).cookie("FG_SESSION",tokens.id()).assertDefault();
    delete.verify();
    assertThat(output.getAll()).doesNotContain("replacement-canary");
  }

  @Test
  void mcpMutationsNeedSessionBoundCsrfBeforeAgentAdapter() throws Exception {
    SessionTokens tokens=login();
    UUID id=UUID.fromString("11111111-1111-4111-8111-111111111111");
    var path=PathParams.create().add("id",id);
    clearInvocations(mcpAdapter);
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.updateMcpConnection("mcp-keep-request.json",403))
        .withPathParameters(path).header("Host",HOST).header("Origin",ORIGIN)
        .cookie("FG_SESSION",tokens.id()).assertDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.enableMcpConnection(403))
        .withPathParameters(path).header("Host",HOST).header("Origin",ORIGIN)
        .cookie("FG_SESSION",tokens.id()).assertDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.reencryptMcpConnection(403))
        .withPathParameters(path).header("Host",HOST).header("Origin",ORIGIN)
        .cookie("FG_SESSION",tokens.id()).assertDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.deleteMcpConnection(403))
        .withPathParameters(path).header("Host",HOST).header("Origin",ORIGIN)
        .cookie("FG_SESSION",tokens.id()).assertDefault();
    verifyNoInteractions(mcpAdapter);
  }

  @Test
  void mcpDetailAfterRemovalMapsUpstream404WithoutRawBody(CapturedOutput output) throws Exception {
    SessionTokens tokens=login();
    UUID id=UUID.fromString("11111111-1111-4111-8111-111111111111");
    var upstream=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.missingMcpConnection())
        .pathPattern(WireMockPathParams.create().add("id",equalTo(id.toString())))
        .header("Authorization",equalTo("Bearer "+encoded(SERVICE))).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.missingMcpConnection())
        .withPathParameters(PathParams.create().add("id",id)).header("Host",HOST)
        .cookie("FG_SESSION",tokens.id())
        .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
            .contains("NOT_FOUND").doesNotContain("upstream-secret-canary"))
        .assertDefault();
    upstream.verify();
    assertThat(output.getAll()).doesNotContain("upstream-secret-canary");
  }

  @Test
  void mcpUpstreamErrorDoesNotPassThroughRawBody(CapturedOutput output) throws Exception {
    SessionTokens tokens=login();
    var upstream=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.failedMcpList())
        .header("Authorization",equalTo("Bearer " + encoded(SERVICE))).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.failedMcpList())
        .header("Host",HOST).cookie("FG_SESSION",tokens.id())
        .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
            .doesNotContain("upstream-secret-canary","RAW_UPSTREAM"))
        .assertDefault();
    upstream.verify();
    assertThat(output.getAll()).doesNotContain("upstream-secret-canary");
  }

  @Test
  void missingSessionRejectsBeforeApplicationAdapter() {
    clearInvocations(agentAdapter);
    manager.mockMvc().ping(NexusOperatorMockMvcEndpoints.rejectedProjects(401))
        .header("Host", HOST).assertDefault();
    verifyNoInteractions(agentAdapter);
  }

  @Test
  void wrongHostEncodedSessionAndMissingCsrfAreRejectedBeforeAgent() throws Exception {
    clearInvocations(agentAdapter);
    manager.mockMvc().ping(NexusOperatorMockMvcEndpoints.rejectedProjects(403))
        .header("Host", "attacker.example").assertDefault();
    manager.mockMvc().ping(NexusOperatorMockMvcEndpoints.encodedSessionPath(401))
        .header("Host", HOST).assertDefault();
    final SessionTokens tokens = login();
    manager.mockMvc().ping(NexusOperatorMockMvcEndpoints.rejectedCancellation(403))
        .withPathParameters(PathParams.create().add("runId", WORKFLOW_ID))
        .header("Host", HOST).header("Origin", ORIGIN)
        .cookie("FG_SESSION", tokens.id()).assertDefault();
    verifyNoInteractions(agentAdapter);
  }

  @Test
  void loginCookieAndCsrfPermitThenLogoutRevokesSession() throws Exception {
    final SessionTokens tokens = login();
    final AtomicReference<String> csrf = new AtomicReference<>();
    manager.mockMvc().ping(NexusOperatorMockMvcEndpoints.currentSession())
        .header("Host", HOST).cookie("FG_SESSION", tokens.id())
        .andExpectPath(result -> csrf.set(new ObjectMapper()
            .readTree(result.getResponse().getContentAsString()).path("csrfToken").asText()))
        .assertDefault();
    assertThat(csrf.get()).isEqualTo(tokens.csrf());

    manager.mockMvc().ping(NexusOperatorMockMvcEndpoints.logout())
        .header("Host", HOST).header("Origin", ORIGIN)
        .header("X-Forge-CSRF", tokens.csrf()).cookie("FG_SESSION", tokens.id())
        .assertDefault();
    manager.mockMvc().ping(NexusOperatorMockMvcEndpoints.rejectedProjects(401))
        .header("Host", HOST).cookie("FG_SESSION", tokens.id()).assertDefault();
  }

  @Test
  void authenticatedTypedProxyUsesOnlyServiceBearerAtAgent() throws Exception {
    final SessionTokens tokens = login();
    final var upstream = manager.wiremock()
        .createMapping(ForgeAgentWireMockEndpoints.getWorkingWorkflow())
        .pathPattern(WireMockPathParams.create().add("workflowRunId", equalTo(WORKFLOW_ID.toString())))
        .header("Authorization", equalTo("Bearer " + encoded(SERVICE)))
        .createDefault();

    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.getWorkingWorkflow())
        .withPathParameters(PathParams.create().add("workflowRunId", WORKFLOW_ID))
        .header("Host", HOST).header("Authorization", "Bearer caller")
        .cookie("FG_SESSION", tokens.id()).assertDefault();
    upstream.verify();
  }

  private SessionTokens login() throws Exception {
    final AtomicReference<SessionTokens> result = new AtomicReference<>();
    manager.mockMvc().ping(NexusOperatorMockMvcEndpoints.login())
        .header("Host", HOST).header("Origin", ORIGIN)
        .andExpectPath(response -> {
          final String cookie = response.getResponse().getHeader("Set-Cookie");
          assertThat(cookie).contains("HttpOnly", "SameSite=Strict", "Path=/")
              .doesNotContain("Domain=");
          final String id = cookie.substring("FG_SESSION=".length(), cookie.indexOf(';'));
          final String csrf = new ObjectMapper().readTree(response.getResponse().getContentAsString())
              .path("csrfToken").asText();
          result.set(new SessionTokens(id, csrf));
        })
        .assertDefault(context -> context.mutateRequest(request ->
            ((ObjectNode) request).put("bootstrapSecret", encoded(BOOTSTRAP))));
    return result.get();
  }

  private static Path credential(String name, byte[] secret) throws Exception {
    final Path file = DIRECTORY.resolve(name);
    Files.writeString(file, encoded(secret));
    Files.setPosixFilePermissions(file,
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    return file;
  }

  private static String encoded(byte[] secret) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
  }

  private record SessionTokens(String id, String csrf) { }
}
