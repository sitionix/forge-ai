package com.sitionix.forgeproxyit.infra;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRequest;
import com.sitionix.forgeai.api.agentproxy.AgentLogDiscoveryRequest;
import com.sitionix.forgeai.api.agentproxy.AgentLogSourceRequest;
import com.sitionix.forgeai.api.agentproxy.AgentLogSourceResponse;
import com.sitionix.forgeai.api.agentproxy.AgentLogTargetCandidateResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectRepositoryResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProjectTaskPageResponse;
import com.sitionix.forgeai.api.agentproxy.AgentRuntimeTargetCandidateResponse;
import com.sitionix.forgeai.api.agentproxy.AgentRuntimeTargetDiscoveryRequest;
import com.sitionix.forgeai.api.agentproxy.AgentSshConnectionRequest;
import com.sitionix.forgeai.api.agentproxy.AgentProjectServiceRequest;
import com.sitionix.forgeai.api.agentproxy.AgentProjectServiceResponse;
import com.sitionix.forgeai.api.agentproxy.AgentServiceRuntimeResponse;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefault;
import org.springframework.http.HttpStatus;

public final class NexusAgentMockMvcEndpoints {

    private NexusAgentMockMvcEndpoints() {
    }

    public static Endpoint<AgentProjectRequest, AgentProjectResponse> createProject() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects",
                HttpMethod.POST,
                AgentProjectRequest.class,
                AgentProjectResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-create-project-request.json")
                        .expectStatus(HttpStatus.CREATED.value())
                        .expectResponse("agent-create-project-response.json")
        );
    }

    public static Endpoint<AgentProjectServiceRequest, AgentProjectServiceResponse> createService() {
        return serviceEndpoint("/api/v1/infrastructure/agents/projects/{projectId}/services", HttpMethod.POST,
                AgentProjectServiceRequest.class, AgentProjectServiceResponse.class, HttpStatus.CREATED,
                "agent-service-response.json", true);
    }

    public static Endpoint<Void, AgentProjectServiceResponse[]> listServices() {
        return serviceEndpoint("/api/v1/infrastructure/agents/projects/{projectId}/services", HttpMethod.GET,
                Void.class, AgentProjectServiceResponse[].class, HttpStatus.OK,
                "agent-service-list-response.json", false);
    }

    public static Endpoint<Void, AgentProjectServiceResponse> getService() {
        return serviceEndpoint("/api/v1/infrastructure/agents/projects/{projectId}/services/{serviceId}", HttpMethod.GET,
                Void.class, AgentProjectServiceResponse.class, HttpStatus.OK,
                "agent-service-response.json", false);
    }

    public static Endpoint<AgentProjectServiceRequest, AgentProjectServiceResponse> updateService() {
        return serviceEndpoint("/api/v1/infrastructure/agents/projects/{projectId}/services/{serviceId}", HttpMethod.PUT,
                AgentProjectServiceRequest.class, AgentProjectServiceResponse.class, HttpStatus.OK,
                "agent-service-response.json", true);
    }

    public static Endpoint<Void, Void> deleteService() {
        return serviceEndpoint("/api/v1/infrastructure/agents/projects/{projectId}/services/{serviceId}", HttpMethod.DELETE,
                Void.class, Void.class, HttpStatus.NO_CONTENT, null, false);
    }

    public static Endpoint<Void, AgentServiceRuntimeResponse> serviceRuntime() {
        return serviceEndpoint("/api/v1/infrastructure/agents/projects/{projectId}/services/{serviceId}/runtime", HttpMethod.GET,
                Void.class, AgentServiceRuntimeResponse.class, HttpStatus.OK,
                "agent-service-runtime-response.json", false);
    }

    public static Endpoint<Void, InfrastructureErrorResponse> serviceRuntimeFailure() {
        return serviceEndpoint("/api/v1/infrastructure/agents/projects/{projectId}/services/{serviceId}/runtime", HttpMethod.GET,
                Void.class, InfrastructureErrorResponse.class, HttpStatus.BAD_GATEWAY,
                "agent-upstream-error-response.json", false);
    }

    public static Endpoint<Void, AgentLogSourceResponse[]> serviceLogs() {
        return serviceEndpoint("/api/v1/infrastructure/agents/projects/{projectId}/services/{serviceId}/log-sources", HttpMethod.GET,
                Void.class, AgentLogSourceResponse[].class, HttpStatus.OK,
                "agent-service-logs-response.json", false);
    }

    private static <Request, Response> Endpoint<Request, Response> serviceEndpoint(
            String path, HttpMethod method, Class<Request> request, Class<Response> response,
            HttpStatus status, String fixture, boolean withRequest) {
        return Endpoint.createContract(path, method, request, response, (MockmvcDefault) context -> {
            context.expectStatus(status.value());
            if (withRequest) context.withRequest("agent-service-request.json");
            if (fixture != null) context.expectResponse(fixture);
        });
    }

    public static Endpoint<Void, AgentProjectTaskPageResponse> listTasks() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/tasks",
                HttpMethod.GET,
                Void.class,
                AgentProjectTaskPageResponse.class,
                (MockmvcDefault) context -> context
                        .expectStatus(HttpStatus.OK.value())
                        .expectResponse("agent-task-page-response.json")
        );
    }

    public static Endpoint<Void, AgentProjectRepositoryResponse> refreshRepository() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/repositories/{repositoryId}/refresh",
                HttpMethod.POST,
                Void.class,
                AgentProjectRepositoryResponse.class,
                (MockmvcDefault) context -> context
                        .expectStatus(HttpStatus.OK.value())
                        .expectResponse("agent-refresh-repository-response.json")
        );
    }

    public static Endpoint<AgentProjectRequest, InfrastructureErrorResponse> createProjectConflict() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects",
                HttpMethod.POST,
                AgentProjectRequest.class,
                InfrastructureErrorResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-create-project-request.json")
                        .expectStatus(HttpStatus.CONFLICT.value())
                        .expectResponse("agent-upstream-error-response.json")
        );
    }

    public static Endpoint<AgentLogSourceRequest, AgentLogSourceResponse> createLogSource() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/log-sources", HttpMethod.POST,
                AgentLogSourceRequest.class, AgentLogSourceResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-create-log-source-request.json")
                        .expectStatus(HttpStatus.CREATED.value())
                        .expectResponse("agent-create-log-source-response.json"));
    }

    public static Endpoint<Void, AgentLogSourceResponse[]> listLogSources() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/log-sources", HttpMethod.GET,
                Void.class, AgentLogSourceResponse[].class,
                (MockmvcDefault) context -> context.expectStatus(HttpStatus.OK.value())
                        .expectResponse("agent-list-log-sources-response.json"));
    }

    public static Endpoint<AgentLogDiscoveryRequest, AgentLogTargetCandidateResponse[]> discoverLogs() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/log-sources/discover", HttpMethod.POST,
                AgentLogDiscoveryRequest.class, AgentLogTargetCandidateResponse[].class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-discover-logs-request.json")
                        .expectStatus(HttpStatus.OK.value())
                        .expectResponse("agent-discover-logs-response.json"));
    }

    public static Endpoint<AgentRuntimeTargetDiscoveryRequest, AgentRuntimeTargetCandidateResponse[]> discoverRuntimeTargets() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/runtime-targets/discover", HttpMethod.POST,
                AgentRuntimeTargetDiscoveryRequest.class, AgentRuntimeTargetCandidateResponse[].class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-discover-runtime-targets-request.json")
                        .expectStatus(HttpStatus.OK.value())
                        .expectResponse("agent-discover-runtime-targets-response.json"));
    }

    public static Endpoint<AgentLogSourceRequest, InfrastructureErrorResponse> createLogSourceConflict() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/log-sources", HttpMethod.POST,
                AgentLogSourceRequest.class, InfrastructureErrorResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-create-log-source-request.json")
                        .expectStatus(HttpStatus.CONFLICT.value())
                        .expectResponse("agent-upstream-error-response.json"));
    }

    public static Endpoint<AgentLogSourceRequest, InfrastructureErrorResponse> invalidLogSource() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/log-sources", HttpMethod.POST,
                AgentLogSourceRequest.class, InfrastructureErrorResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-invalid-log-source-request.json")
                        .expectStatus(HttpStatus.BAD_REQUEST.value()));
    }

    public static Endpoint<AgentSshConnectionRequest, Void> testSshConnection() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/ssh-connections/test",
                HttpMethod.POST, AgentSshConnectionRequest.class, Void.class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-test-ssh-connection-request.json")
                        .expectStatus(HttpStatus.NO_CONTENT.value()));
    }

    public static Endpoint<AgentSshConnectionRequest, InfrastructureErrorResponse>
            testSshConnectionFailure() {
        return Endpoint.createContract(
                "/api/v1/infrastructure/agents/projects/{projectId}/ssh-connections/test",
                HttpMethod.POST, AgentSshConnectionRequest.class, InfrastructureErrorResponse.class,
                (MockmvcDefault) context -> context
                        .withRequest("agent-test-ssh-connection-request.json")
                        .expectStatus(HttpStatus.BAD_GATEWAY.value())
                        .expectResponse("agent-upstream-error-response.json"));
    }
}
