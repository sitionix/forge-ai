package com.sitionix.forgeagent.it.tests;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sitionix.forgeagent.api.ForgeAgentController;
import com.sitionix.forgeagent.domain.port.McpCredentialCipher;
import com.sitionix.forgeagent.domain.port.McpConnectionRepository;
import com.sitionix.forgeagent.application.mcp.McpConnectionService;
import com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeBoundaryVerifier;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import com.sitionix.forgeit.core.test.IntegrationTest;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Synthetic web guard proof; the privileged OS boundary has a separate fixture. */
@IntegrationTest(properties = "forge.mcp.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
class AgentMcpManagementGuardIT {
  private static final byte[] SERVICE = new byte[32];
  private static final Path DIRECTORY;
  private static final Path SERVICE_FILE;
  private static final Path KEY_FILE;
  private static final Path DB_FILE;
  static {
    try {
      Arrays.fill(SERVICE, (byte) 9);
      DIRECTORY = Files.createTempDirectory("agent-guard-it");
      SERVICE_FILE = file("service", Base64.getUrlEncoder().withoutPadding().encodeToString(SERVICE));
      KEY_FILE = file("key", "active=k1\nkey.k1=" + Base64.getEncoder().encodeToString(new byte[32]) + "\n");
      DB_FILE = file("db", "forge-it");
    } catch (Exception exception) {
      throw new IllegalStateException("Synthetic credential setup failed");
    }
  }

  @DynamicPropertySource
  static void credentials(DynamicPropertyRegistry registry) {
    registry.add("forge.mcp.service-credential-file", SERVICE_FILE::toString);
    registry.add("forge.mcp.key-file", KEY_FILE::toString);
    registry.add("forge.mcp.database-credential-file", DB_FILE::toString);
  }

  @AfterAll
  static void cleanup() throws Exception {
    Files.deleteIfExists(SERVICE_FILE);
    Files.deleteIfExists(KEY_FILE);
    Files.deleteIfExists(DB_FILE);
    Files.deleteIfExists(DIRECTORY);
  }

  @Autowired ForgeAgentTestManager manager;
  @MockBean RuntimeBoundaryVerifier verifier;
  @SpyBean ForgeAgentController controller;
  @SpyBean McpCredentialCipher cipher;
  @SpyBean McpConnectionRepository mcpRepository;
  @Autowired McpConnectionService mcpService;
  @Autowired JdbcTemplate jdbc;

  @Test
  void cipherAndDatabaseFailuresHaveStaticHttpAndLogBoundary(CapturedOutput output) {
    String bearer="Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SERVICE);
    try {
      org.mockito.Mockito.doThrow(new IllegalStateException("cipher-error-canary"))
          .when(cipher).encrypt(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(byte[].class));
      manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION_ERROR)
          .header("Authorization",bearer).withRequest("mcp-create-request.json")
          .expectStatus(HttpStatus.INTERNAL_SERVER_ERROR)
          .andExpectPath(result -> {
            String body=result.getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("cipher-error-canary","agent-canary-secret");
            var error=new ObjectMapper().readTree(body);
            org.assertj.core.api.Assertions.assertThat(error.path("code").asText()).isEqualTo("MCP_OPERATION_FAILED");
            org.assertj.core.api.Assertions.assertThat(error.path("message").asText()).isEqualTo("MCP management operation failed.");
            org.assertj.core.api.Assertions.assertThat(error.path("correlationId").isNull()).isTrue();
          })
          .assertAndCreate();
    } finally { org.mockito.Mockito.reset(cipher); }
    try {
      org.mockito.Mockito.doThrow(new IllegalStateException("database-error-canary"))
          .when(mcpRepository).findAll(org.mockito.ArgumentMatchers.any());
      manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.LIST_MCP_CONNECTIONS)
          .header("Authorization",bearer).expectStatus(HttpStatus.INTERNAL_SERVER_ERROR)
          .andExpectPath(result -> {
            String body=result.getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(body).doesNotContain("database-error-canary");
            var error=new ObjectMapper().readTree(body);
            org.assertj.core.api.Assertions.assertThat(error.path("code").asText()).isEqualTo("MCP_OPERATION_FAILED");
            org.assertj.core.api.Assertions.assertThat(error.path("message").asText()).isEqualTo("MCP management operation failed.");
          })
          .assertAndCreate();
    } finally { org.mockito.Mockito.reset(mcpRepository); }
    org.assertj.core.api.Assertions.assertThat(output.getAll())
        .doesNotContain("cipher-error-canary","database-error-canary","agent-canary-secret");
  }

  @Test
  void absentWrongAndEncodedControlPathsRejectBeforeController() {
    clearInvocations(controller);
    manager.mockMvc().ping(projects()).expectStatus(HttpStatus.UNAUTHORIZED).assertAndCreate();
    manager.mockMvc().ping(projects()).header("Authorization", "Bearer wrong")
        .expectStatus(HttpStatus.UNAUTHORIZED).assertAndCreate();
    manager.mockMvc().ping(encodedProjects()).expectStatus(HttpStatus.UNAUTHORIZED).assertAndCreate();
    manager.mockMvc().ping(stream()).expectStatus(HttpStatus.UNAUTHORIZED).assertAndCreate();
    verifyNoInteractions(controller);
  }

  @Test
  void serviceBearerAllowsExistingProjectsRoute() {
    manager.mockMvc().ping(projects())
        .header("Authorization", "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SERVICE))
        .expectStatus(HttpStatus.OK).assertAndCreate();
  }

  @Test
  void typedMcpCrudAndSecretRedaction(CapturedOutput output) throws Exception {
    String bearer="Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SERVICE);
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.LIST_MCP_CONNECTIONS)
        .expectStatus(HttpStatus.UNAUTHORIZED).assertAndCreate();
    AtomicReference<UUID> id=new AtomicReference<>();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION)
        .header("Authorization",bearer).withRequest("mcp-create-request.json").expectStatus(HttpStatus.CREATED)
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
        .withPathParameters(path).header("Authorization",bearer).expectStatus(HttpStatus.OK)
        .andExpectPath(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
            .doesNotContain("agent-canary-secret","ciphertext")).assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.UPDATE_MCP_CONNECTION)
        .withPathParameters(path).header("Authorization",bearer).withRequest("mcp-update-request.json")
        .expectStatus(HttpStatus.OK).assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.GET_MCP_CONNECTION)
        .withPathParameters(path).header("Authorization",bearer).expectStatus(HttpStatus.OK)
        .andExpectPath(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
            .contains("test-2","\"scope\":\"ALL\"").doesNotContain("agent-canary-secret"))
        .assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.ENABLE_MCP_CONNECTION)
        .withPathParameters(path).header("Authorization",bearer).withRequest("mcp-enable-request.json")
        .expectStatus(HttpStatus.OK).assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.GET_MCP_CONNECTION)
        .withPathParameters(path).header("Authorization",bearer).expectStatus(HttpStatus.OK)
        .andExpectPath(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
            .contains("\"enabled\":true"))
        .assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.REENCRYPT_MCP_CONNECTION)
        .withPathParameters(path).header("Authorization",bearer).expectStatus(HttpStatus.NO_CONTENT).assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.DELETE_MCP_CONNECTION)
        .withPathParameters(path).header("Authorization",bearer).expectStatus(HttpStatus.NO_CONTENT).assertAndCreate();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.GET_MCP_CONNECTION_ERROR)
        .withPathParameters(path).header("Authorization",bearer).expectStatus(HttpStatus.NOT_FOUND).assertAndCreate();
    org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("agent-canary-secret");
  }

  @Test
  void missingOrWrongOldKeyFailsHttpRotationWithoutChangingCiphertext(CapturedOutput output) throws Exception {
    String bearer="Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SERVICE);
    String original=Files.readString(KEY_FILE);
    AtomicReference<UUID> id=new AtomicReference<>();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION)
        .header("Authorization",bearer).withRequest("mcp-rotation-create-request.json")
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
            .withPathParameters(path).header("Authorization",bearer)
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
    String bearer="Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SERVICE);
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION_ERROR)
        .header("Authorization",bearer).withRequest("mcp-malformed-credential-request.json")
        .expectStatus(HttpStatus.BAD_REQUEST)
        .andExpectPath(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
            .contains("INVALID_REQUEST").doesNotContain("agent-malformed-canary"))
        .assertAndCreate();
    org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("agent-malformed-canary");
  }

  @Test
  void replaceRequiresCredentialEnvelope() {
    String bearer="Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SERVICE);
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION_ERROR)
        .header("Authorization",bearer).withRequest("mcp-null-replace-request.json")
        .expectStatus(HttpStatus.BAD_REQUEST).assertAndCreate();
  }

  @Test
  void bearerCanBeCreatedDisabledWithoutConfiguredCredential() throws Exception {
    String bearer="Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SERVICE);
    AtomicReference<UUID> id=new AtomicReference<>();
    manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION)
        .header("Authorization",bearer).withRequest("mcp-unconfigured-create-request.json")
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
    String bearer="Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(SERVICE);
    for(String fixture:new String[]{"mcp-unknown-transport-request.json","mcp-tool-approval-request.json"}){
      manager.mockMvc().ping(ForgeAgentMockMvcEndpoint.CREATE_MCP_CONNECTION_ERROR)
          .header("Authorization",bearer).withRequest(fixture)
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
