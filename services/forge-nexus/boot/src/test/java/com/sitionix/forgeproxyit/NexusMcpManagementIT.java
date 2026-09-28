package com.sitionix.forgeproxyit;

import com.sitionix.forgeai.Application;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeproxyit.infra.NexusProxyTestManagerImpl;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

import static com.sitionix.forgeit.wiremock.api.Parameter.equalTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentClientAdapter;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentMcpClientAdapter;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentClientProperties;
import com.sitionix.forgeai.infrastructure.agentclient.McpAvailableCatalogAdapter;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import com.sitionix.forgeit.wiremock.api.WireMockPathParams;
import com.sitionix.forgeproxyit.infra.ForgeAgentWireMockEndpoints;
import com.sitionix.forgeproxyit.infra.NexusAgentMockMvcEndpoints;
import com.sitionix.forgeproxyit.infra.NexusProxyTestManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

@IntegrationTest(properties = {

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
class NexusMcpManagementIT {
  private static final String ORIGIN = "http://127.0.0.1:9099";
  private static final String HOST = "127.0.0.1:9099";
  private static final UUID WORKFLOW_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  @Autowired NexusProxyTestManager manager;
  @SpyBean ForgeAgentClientAdapter agentAdapter;
  @SpyBean ForgeAgentMcpClientAdapter mcpAdapter;
  @SpyBean McpAvailableCatalogAdapter availableAdapter;
  @Autowired ForgeAgentClientProperties agentClientProperties;


  @Test
  void mcpRouteRejectsUnsafeInputLocally(CapturedOutput output) throws Exception {
    clearInvocations(mcpAdapter);

    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.invalidMcpId())
        .assertDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.createMcpConnection(400))
        
        .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
            .doesNotContain("nexus-canary-secret"))
        .assertDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.createMcpConnection(400,"mcp-malformed-credential-request.json"))
        
        .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
            .contains("INVALID_REQUEST","MCP request is invalid.").doesNotContain("nexus-malformed-canary"))
        .assertDefault();
    for(String fixture:new String[]{"mcp-unknown-transport-request.json","mcp-tool-approval-request.json"}){
      manager.mockMvc().ping(NexusAgentMockMvcEndpoints.createMcpConnection(400,fixture))
          .assertDefault();
    }
    verifyNoInteractions(mcpAdapter);
    assertThat(output.getAll()).doesNotContain("nexus-canary-secret","nexus-malformed-canary");
  }

  @Test
  void emptyCredentialEnvelopeIsRejectedBeforeAgentMutation() throws Exception {
    clearInvocations(mcpAdapter);

    for (String fixture : new String[]{"mcp-empty-keep-request.json","mcp-null-remove-request.json"}) {
      manager.mockMvc().ping(NexusAgentMockMvcEndpoints.invalidMcpUpdate(fixture))
          .withPathParameters(PathParams.create().add("id",WORKFLOW_ID))
          .assertDefault();
    }
    verifyNoInteractions(mcpAdapter);
  }

  @Test
  void mcpMetadataWorksWithoutAuthentication() throws Exception {

    final var upstream=manager.wiremock()
        .createMapping(ForgeAgentWireMockEndpoints.listMcpConnections())
        .createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.listMcpConnections(200))
        .assertDefault();
    upstream.verify();
  }

  @Test
  void authenticatedMcpCreateForwardsCredentialOnlyInAgentBody(CapturedOutput output) throws Exception {

    final var upstream=manager.wiremock()
        .createMapping(ForgeAgentWireMockEndpoints.createMcpConnection())
        .createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.createValidMcpConnection())
        
        .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
            .contains("credentialConfigured").doesNotContain("nexus-canary-secret","ciphertext"))
        .assertDefault();
    upstream.verify();
    assertThat(output.getAll()).doesNotContain("nexus-canary-secret");
  }

  @Test
  void mcpDetailUpdatesEnableReencryptAndDeleteUseTypedGuardedRoutes(CapturedOutput output) throws Exception {

    UUID id=UUID.fromString("11111111-1111-4111-8111-111111111111");
    var path=PathParams.create().add("id",id);
    var wirePath=WireMockPathParams.create().add("id",equalTo(id.toString()));


    var get=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.getMcpConnection())
        .pathPattern(wirePath).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.getMcpConnection(200))
        .withPathParameters(path)
        .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
            .contains("credentialConfigured","STREAMABLE_HTTP").doesNotContain("nexus-canary-secret"))
        .assertDefault();
    get.verify();

    String[][] changes={{"mcp-keep-request.json","agent-mcp-keep-request.json"},
        {"mcp-replace-request.json","agent-mcp-replace-request.json"},
        {"mcp-remove-request.json","agent-mcp-remove-request.json"}};
    for(String[] change:changes){
      var upstream=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.updateMcpConnection(change[1]))
          .pathPattern(wirePath).createDefault();
      manager.mockMvc().ping(NexusAgentMockMvcEndpoints.updateMcpConnection(change[0],200))
          .withPathParameters(path)
          .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
              .contains("updated").doesNotContain("replacement-canary"))
          .assertDefault();
      upstream.verify();
    }

    var enable=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.enableMcpConnection())
        .pathPattern(wirePath).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.enableMcpConnection(200))
        .withPathParameters(path)
        .andExpectPath(result -> assertThat(new ObjectMapper().readTree(result.getResponse().getContentAsString())
            .path("enabled").asBoolean()).isTrue())
        .assertDefault();
    enable.verify();

    var rotate=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.reencryptMcpConnection())
        .pathPattern(wirePath).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.reencryptMcpConnection(204))
        .withPathParameters(path)
        .assertDefault();
    rotate.verify();

    var delete=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.deleteMcpConnection())
        .pathPattern(wirePath).createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.deleteMcpConnection(204))
        .withPathParameters(path)
        .assertDefault();
    delete.verify();
    assertThat(output.getAll()).doesNotContain("replacement-canary");
  }


  @Test
  void mcpDetailAfterRemovalMapsUpstream404WithoutRawBody(CapturedOutput output) throws Exception {

    UUID id=UUID.fromString("11111111-1111-4111-8111-111111111111");
    var upstream=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.missingMcpConnection())
        .pathPattern(WireMockPathParams.create().add("id",equalTo(id.toString())))
        .createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.missingMcpConnection())
        .withPathParameters(PathParams.create().add("id",id))
        .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
            .contains("NOT_FOUND","MCP connection not found.").doesNotContain("upstream-secret-canary"))
        .assertDefault();
    upstream.verify();
    assertThat(output.getAll()).doesNotContain("upstream-secret-canary");
  }

  @Test
  void mcpAgent500PreservesSafeErrorContract(CapturedOutput output) throws Exception {

    var upstream=manager.wiremock().createMapping(ForgeAgentWireMockEndpoints.failedMcpList())
        .createDefault();
    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.failedMcpList())
        .andExpectPath(result -> assertThat(result.getResponse().getContentAsString())
            .contains("MCP_OPERATION_FAILED","MCP management operation failed.")
            .doesNotContain("upstream-secret-canary"))
        .assertDefault();
    upstream.verify();
    assertThat(output.getAll()).doesNotContain("upstream-secret-canary");
  }

  @Test
  void mcpAdvicePreservesTransportWrapperWithoutPublishingItsRawCause(CapturedOutput output) throws Exception {

    var error = new com.sitionix.forgeai.domain.exception.AgentClientException(409,
        "{\"code\":\"DEPENDENCY_CYCLE\",\"message\":\"cycle\",\"correlationId\":\"corr-raw\"}",
        java.util.Map.of("X-Secret", java.util.List.of("header-canary")),
        new IllegalStateException("cause-canary"));
    org.mockito.Mockito.doThrow(error).when(mcpAdapter).list();
    try {
      manager.mockMvc().ping(NexusAgentMockMvcEndpoints.mcpListError(409))
          .andExpectPath(result -> {
            var body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
            assertThat(body.path("code").asText()).isEqualTo("DEPENDENCY_CYCLE");
            assertThat(body.path("message").asText()).isEqualTo("cycle");
            assertThat(body.path("correlationId").asText()).isEqualTo("corr-raw");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("header-canary", "cause-canary");
          }).assertDefault();
    } finally {
      org.mockito.Mockito.reset(mcpAdapter);
    }
    assertThat(output.getAll()).doesNotContain("header-canary", "cause-canary");
  }

  @Test
  void typedProxyNeedsNoServiceBearer() throws Exception {

    final var upstream = manager.wiremock()
        .createMapping(ForgeAgentWireMockEndpoints.getWorkingWorkflow())
        .pathPattern(WireMockPathParams.create().add("workflowRunId", equalTo(WORKFLOW_ID.toString())))
        .createDefault();

    manager.mockMvc().ping(NexusAgentMockMvcEndpoints.getWorkingWorkflow())
        .withPathParameters(PathParams.create().add("workflowRunId", WORKFLOW_ID))
        .assertDefault();
    upstream.verify();
  }


}
