package com.sitionix.forgeproxyit.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefault;

public final class NexusOperatorMockMvcEndpoints {
  private NexusOperatorMockMvcEndpoints() { }

  public static Endpoint<JsonNode, JsonNode> login() {
    return Endpoint.createContract("/api/v1/operator/session", HttpMethod.POST,
        JsonNode.class, JsonNode.class,
        (MockmvcDefault) context -> context.withRequest("operator-login-request.json").expectStatus(200));
  }

  public static Endpoint<Void, JsonNode> currentSession() {
    return Endpoint.createContract("/api/v1/operator/session", HttpMethod.GET,
        Void.class, JsonNode.class, (MockmvcDefault) context -> context.expectStatus(200));
  }

  public static Endpoint<Void, Void> logout() {
    return Endpoint.createContract("/api/v1/operator/session", HttpMethod.DELETE,
        Void.class, Void.class, (MockmvcDefault) context -> context.expectStatus(204));
  }

  public static Endpoint<Void, Void> rejectedProjects(int status) {
    return Endpoint.createContract("/api/v1/infrastructure/agents/projects", HttpMethod.GET,
        Void.class, Void.class, (MockmvcDefault) context -> context.expectStatus(status));
  }

  public static Endpoint<Void, Void> rejectedCancellation(int status) {
    return Endpoint.createContract(
        "/api/v1/infrastructure/agents/workflow-runs/{runId}/cancel", HttpMethod.POST,
        Void.class, Void.class, (MockmvcDefault) context -> context.expectStatus(status));
  }

  public static Endpoint<Void, Void> encodedSessionPath(int status) {
    return Endpoint.createContract("/api/v1/operator/%73ession", HttpMethod.GET,
        Void.class, Void.class, (MockmvcDefault) context -> context.expectStatus(status));
  }

}
