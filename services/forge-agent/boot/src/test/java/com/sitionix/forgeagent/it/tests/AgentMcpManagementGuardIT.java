package com.sitionix.forgeagent.it.tests;

import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sitionix.forgeagent.api.ForgeAgentController;
import com.sitionix.forgeagent.domain.port.McpCredentialCipher;
import com.sitionix.forgeagent.domain.port.McpConnectionRepository;
import com.sitionix.forgeagent.domain.port.McpRemoteProbe;
import com.sitionix.forgeagent.domain.model.McpAuthType;
import com.sitionix.forgeagent.domain.model.McpCredentialSecret;
import com.sitionix.forgeagent.domain.model.McpProjectAccess;
import com.sitionix.forgeagent.application.mcp.McpConnectionService;
import com.sitionix.forgeagent.application.mcp.McpProbeService;
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeBoundaryVerifier;
import com.sitionix.forgeagent.infrastructure.local.mcp.registry.McpRegistryHttpClient;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** MCP CRUD and safe errors without Forge login; OS isolation has a separate fixture. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AgentMcpManagementGuardIT {
  private static final Path DIRECTORY;
  private static final Path KEY_FILE;
  private static final Path DB_FILE;
  static {
    try {
      DIRECTORY = Files.createTempDirectory("agent-guard-it");
      KEY_FILE = file("key", "active=k1\nkey.k1=" + Base64.getEncoder().encodeToString(new byte[32]) + "\n");
      DB_FILE = file("db", "forge-it");
    } catch (Exception exception) {
      throw new IllegalStateException("Synthetic credential setup failed");
    }
  }

  @DynamicPropertySource
  static void credentials(DynamicPropertyRegistry registry) {
    registry.add("forge.mcp.key-file", KEY_FILE::toString);
    registry.add("forge.mcp.database-credential-file", DB_FILE::toString);
  }

  @AfterAll
  static void cleanup() throws Exception {
    Files.deleteIfExists(KEY_FILE);
    Files.deleteIfExists(DB_FILE);
    Files.deleteIfExists(DIRECTORY);
  }

  @Autowired ForgeAgentTestManager manager;
  @MockBean RuntimeBoundaryVerifier verifier;
  @SpyBean ForgeAgentController controller;
  @SpyBean McpCredentialCipher cipher;
  @SpyBean McpConnectionRepository mcpRepository;
  @MockBean McpRegistryHttpClient registryClient;
  @Autowired McpConnectionService mcpService;
  @SpyBean McpProbeService probeService;
  @SpyBean McpRemoteProbe remoteProbe;
  @Autowired JdbcTemplate jdbc;


  @Test void invalidProbeApprovalStopsBeforeService() {
    var path = PathParams.create().add("id", UUID.randomUUID());
    clearInvocations(probeService);
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.APPROVE_MCP_TOOLS_ERROR)
        .withPathParameters(path).withRequest("mcp-invalid-approve-request.json")
        .expectStatus(HttpStatus.BAD_REQUEST).assertAndCreate();
    verifyNoInteractions(probeService);
  }

  @Test void probeFailureNeverPublishesTransportCauseOrCredential(CapturedOutput output) throws Exception {
    var connection = mcpService.create("canary", java.net.URI.create("https://example.org/mcp"),
        McpAuthType.BEARER, McpProjectAccess.all(), McpCredentialSecret.bearer("synthetic-probe-secret"));
    try {
      org.mockito.Mockito.doThrow(new IllegalStateException("upstream-cause-canary"))
          .when(remoteProbe).probe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(byte[].class));
      manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.TEST_MCP_CONNECTION_ERROR)
          .withPathParameters(PathParams.create().add("id", connection.id()))
          .expectStatus(HttpStatus.INTERNAL_SERVER_ERROR)
          .andExpectPath(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
              .contains("MCP_OPERATION_FAILED").doesNotContain("synthetic-probe-secret", "upstream-cause-canary"))
          .assertAndCreate();
      org.assertj.core.api.Assertions.assertThat(output.getAll())
          .doesNotContain("synthetic-probe-secret", "upstream-cause-canary");
    } finally {
      org.mockito.Mockito.reset(remoteProbe);
      mcpService.remove(connection.id());
    }
  }

  @Test
  void availableCatalogRejectsInvalidLimitBeforeRegistry() {
    clearInvocations(registryClient);
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.LIST_MCP_AVAILABLE_INVALID)
        .expectStatus(HttpStatus.BAD_REQUEST).assertAndCreate();
    verifyNoInteractions(registryClient);
  }

  @Test
  void projectsNeedNoServiceBearer() {
    manager.mockMvc().ping(projects())
        .expectStatus(HttpStatus.OK).assertAndCreate();
  }

  @Test
  void typedMcpCrudAndSecretRedaction(CapturedOutput output) throws Exception {
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.LIST_MCP_CONNECTIONS)
        .expectStatus(HttpStatus.OK).assertAndCreate();
    AtomicReference<UUID> id=new AtomicReference<>();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION)
        .withRequest("mcp-create-request.json").expectStatus(HttpStatus.CREATED)
        .andExpectPath(result -> {
          String body=result.getResponse().getContentAsString();
          org.assertj.core.api.Assertions.assertThat(body).doesNotContain("agent-canary-secret","ciphertext");
          var json=new ObjectMapper().readTree(body);
          org.assertj.core.api.Assertions.assertThat(json.path("enabled").asBoolean()).isFalse();
          org.assertj.core.api.Assertions.assertThat(json.path("credentialConfigured").asBoolean()).isTrue();
          org.assertj.core.api.Assertions.assertThat(json.path("transport").asText()).isEqualTo("STREAMABLE_HTTP");
          id.set(UUID.fromString(json.path("id").asText()));
        }).assertAndCreate();
    PathParams path=PathParams.create().add("id",id.get());
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.GET_MCP_CONNECTION)
        .withPathParameters(path).expectStatus(HttpStatus.OK)
        .andExpectPath(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
            .doesNotContain("agent-canary-secret","ciphertext")).assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.UPDATE_MCP_CONNECTION)
        .withPathParameters(path).withRequest("mcp-update-request.json")
        .expectStatus(HttpStatus.OK).assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.GET_MCP_CONNECTION)
        .withPathParameters(path).expectStatus(HttpStatus.OK)
        .andExpectPath(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
            .contains("test-2","\"scope\":\"ALL\"").doesNotContain("agent-canary-secret"))
        .assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.ENABLE_MCP_CONNECTION)
        .withPathParameters(path).withRequest("mcp-enable-request.json")
        .expectStatus(HttpStatus.OK).assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.GET_MCP_CONNECTION)
        .withPathParameters(path).expectStatus(HttpStatus.OK)
        .andExpectPath(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
            .contains("\"enabled\":true"))
        .assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.REENCRYPT_MCP_CONNECTION)
        .withPathParameters(path).expectStatus(HttpStatus.NO_CONTENT).assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.DELETE_MCP_CONNECTION)
        .withPathParameters(path).expectStatus(HttpStatus.NO_CONTENT).assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.GET_MCP_CONNECTION_ERROR)
        .withPathParameters(path).expectStatus(HttpStatus.NOT_FOUND).assertAndCreate();
    org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("agent-canary-secret");
  }

  @Test
  void missingOrWrongOldKeyFailsHttpRotationWithoutChangingCiphertext(CapturedOutput output) throws Exception {
    String original=Files.readString(KEY_FILE);
    AtomicReference<UUID> id=new AtomicReference<>();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION)
        .withRequest("mcp-rotation-create-request.json")
        .expectStatus(HttpStatus.CREATED)
        .andExpectPath(result -> id.set(UUID.fromString(new ObjectMapper()
            .readTree(result.getResponse().getContentAsString()).path("id").asText())))
        .assertAndCreate();
    byte[] before=jdbc.queryForObject("SELECT ciphertext FROM mcp_connection_credentials WHERE connection_id=?",
        byte[].class,id.get());
    String oldId=jdbc.queryForObject("SELECT key_id FROM mcp_connection_credentials WHERE connection_id=?",
        String.class,id.get());
    PathParams path=PathParams.create().add("id",id.get());
    String newKey=Base64.getEncoder().encodeToString(new byte[32]);
    byte[] wrongBytes=new byte[32]; Arrays.fill(wrongBytes,(byte)1);
    try {
      for(String keyFile : new String[]{"active=new\nkey.new="+newKey+"\n",
          "active=new\nkey.new="+newKey+"\nkey."+oldId+"="+
              Base64.getEncoder().encodeToString(wrongBytes)+"\n"}) {
        Files.writeString(KEY_FILE,keyFile);
        manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.REENCRYPT_MCP_CONNECTION_ERROR)
            .withPathParameters(path)
            .expectStatus(HttpStatus.INTERNAL_SERVER_ERROR)
            .andExpectPath(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .doesNotContain("rotation-canary","ciphertext","key.new"))
            .assertAndCreate();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT ciphertext FROM mcp_connection_credentials WHERE connection_id=?",byte[].class,id.get()))
            .isEqualTo(before);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT key_id FROM mcp_connection_credentials WHERE connection_id=?",String.class,id.get()))
            .isEqualTo(oldId);
      }
    } finally {
      Files.writeString(KEY_FILE,original);
      mcpService.remove(id.get());
    }
    org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("rotation-canary");
  }

  @Test
  void malformedNestedCredentialHasSafeError(CapturedOutput output) {
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION_ERROR)
        .withRequest("mcp-malformed-credential-request.json")
        .expectStatus(HttpStatus.BAD_REQUEST)
        .andExpectPath(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
            .contains("INVALID_REQUEST").doesNotContain("agent-malformed-canary"))
        .assertAndCreate();
    org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("agent-malformed-canary");
  }

  @Test
  void replaceRequiresCredentialEnvelope() {
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION_ERROR)
        .withRequest("mcp-null-replace-request.json")
        .expectStatus(HttpStatus.BAD_REQUEST).assertAndCreate();
  }

  @Test
  void bearerCanBeCreatedDisabledWithoutConfiguredCredential() throws Exception {
    AtomicReference<UUID> id=new AtomicReference<>();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION)
        .withRequest("mcp-unconfigured-create-request.json")
        .expectStatus(HttpStatus.CREATED)
        .andExpectPath(result -> {
          var json=new ObjectMapper().readTree(result.getResponse().getContentAsString());
          org.assertj.core.api.Assertions.assertThat(json.path("enabled").asBoolean()).isFalse();
          org.assertj.core.api.Assertions.assertThat(json.path("credentialConfigured").asBoolean()).isFalse();
          id.set(UUID.fromString(json.path("id").asText()));
        }).assertAndCreate();
    mcpService.remove(id.get());
  }

  @Test
  void unsupportedTransportAndUnknownToolApprovalsAreRejected() {
    for(String fixture:new String[]{"mcp-unknown-transport-request.json","mcp-tool-approval-request.json"}){
      manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION_ERROR)
          .withRequest(fixture)
          .expectStatus(HttpStatus.BAD_REQUEST).assertAndCreate();
    }
  }

  private static Path file(String name, String contents) throws Exception {
    final Path file = DIRECTORY.resolve(name);
    Files.writeString(file, contents);
    Files.setPosixFilePermissions(file,
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    return file;
  }

  private static Endpoint<Void, Void> projects() {
    return Endpoint.createContract("/api/v1/projects", HttpMethod.GET, Void.class, Void.class);
  }

  private static Endpoint<Void, Void> encodedProjects() {
    return Endpoint.createContract("/%61pi/v1/projects", HttpMethod.GET, Void.class, Void.class);
  }

  private static Endpoint<Void, Void> stream() {
    return Endpoint.createContract("/api/v1/projects/11111111-1111-4111-8111-111111111111/logs/stream",
        HttpMethod.GET, Void.class, Void.class);
  }
}
