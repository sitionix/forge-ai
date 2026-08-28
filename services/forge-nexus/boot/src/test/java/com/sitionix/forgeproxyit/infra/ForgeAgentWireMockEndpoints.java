package com.sitionix.forgeproxyit.infra;

import com.sitionix.forgeai.api.activeprofile.InfrastructureErrorResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogDiscoveryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogSourceRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogSourceResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogTargetCandidateResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentSshConnectionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RuntimeTargetCandidateResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RuntimeTargetDiscoveryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskPageResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectServiceRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectServiceResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ServiceRuntimeResponse;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.domain.endpoint.HttpMethod;
import com.sitionix.forgeit.domain.endpoint.wiremock.WiremockDefault;
import org.springframework.http.HttpStatus;

public final class ForgeAgentWireMockEndpoints {

    private ForgeAgentWireMockEndpoints() {
    }

    public static Endpoint<AgentProjectRequest, AgentProjectResponse> createProject() {
        return upstreamPost(AgentProjectResponse.class, HttpStatus.CREATED, "agent-create-project-response.json");
    }

    public static Endpoint<ProjectServiceRequest, ProjectServiceResponse> createService() {
        return serviceEndpoint("/api/v1/projects/{projectId}/services", HttpMethod.POST,
                ProjectServiceRequest.class, ProjectServiceResponse.class, HttpStatus.CREATED,
                "agent-service-response.json", true);
    }

    public static Endpoint<Void, ProjectServiceResponse[]> listServices() {
        return serviceEndpoint("/api/v1/projects/{projectId}/services", HttpMethod.GET,
                Void.class, ProjectServiceResponse[].class, HttpStatus.OK,
                "agent-service-list-response.json", false);
    }

    public static Endpoint<Void, ProjectServiceResponse> getService() {
        return serviceEndpoint("/api/v1/projects/{projectId}/services/{serviceId}", HttpMethod.GET,
                Void.class, ProjectServiceResponse.class, HttpStatus.OK,
                "agent-service-response.json", false);
    }

    public static Endpoint<ProjectServiceRequest, ProjectServiceResponse> updateService() {
        return serviceEndpoint("/api/v1/projects/{projectId}/services/{serviceId}", HttpMethod.PUT,
                ProjectServiceRequest.class, ProjectServiceResponse.class, HttpStatus.OK,
                "agent-service-response.json", true);
    }

    public static Endpoint<Void, Void> deleteService() {
        return serviceEndpoint("/api/v1/projects/{projectId}/services/{serviceId}", HttpMethod.DELETE,
                Void.class, Void.class, HttpStatus.NO_CONTENT, null, false);
    }

    public static Endpoint<Void, ServiceRuntimeResponse> serviceRuntime() {
        return serviceEndpoint("/api/v1/projects/{projectId}/services/{serviceId}/runtime", HttpMethod.GET,
                Void.class, ServiceRuntimeResponse.class, HttpStatus.OK,
                "agent-service-runtime-response.json", false);
    }

    public static Endpoint<Void, InfrastructureErrorResponse> serviceRuntimeFailure() {
        return serviceEndpoint("/api/v1/projects/{projectId}/services/{serviceId}/runtime", HttpMethod.GET,
                Void.class, InfrastructureErrorResponse.class, HttpStatus.BAD_GATEWAY,
                "agent-upstream-error-response.json", false);
    }

    public static Endpoint<Void, AgentLogSourceResponse[]> serviceLogs() {
        return serviceEndpoint("/api/v1/projects/{projectId}/services/{serviceId}/log-sources", HttpMethod.GET,
                Void.class, AgentLogSourceResponse[].class, HttpStatus.OK,
                "agent-service-logs-response.json", false);
    }

    private static <Request, Response> Endpoint<Request, Response> serviceEndpoint(
            String path, HttpMethod method, Class<Request> request, Class<Response> response,
            HttpStatus status, String fixture, boolean matchRequest) {
        return Endpoint.createContract(path, method, request, response, (WiremockDefault) context -> {
            context.plainUrl().responseStatus(status.value());
            if (matchRequest) context.matchesJson("agent-service-request.json");
            if (fixture != null) context.responseBody(fixture);
        });
    }

    public static Endpoint<Void, ProjectTaskPageResponse> listTasks() {
        return Endpoint.createContract(
                "/api/v1/projects/{projectId}/tasks",
                HttpMethod.GET,
                Void.class,
                ProjectTaskPageResponse.class,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("agent-task-page-response.json")
        );
    }

    public static Endpoint<Void, ProjectRepositoryResponse> refreshRepository() {
        return Endpoint.createContract(
                "/api/v1/projects/{projectId}/repositories/{repositoryId}/refresh",
                HttpMethod.POST,
                Void.class,
                ProjectRepositoryResponse.class,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("agent-refresh-repository-response.json")
        );
    }

    public static Endpoint<AgentProjectRequest, InfrastructureErrorResponse> createProjectConflict() {
        return upstreamPost(InfrastructureErrorResponse.class, HttpStatus.CONFLICT,
                "agent-upstream-error-response.json");
    }

    public static Endpoint<AgentLogSourceRequest, AgentLogSourceResponse> createLogSource() {
        return Endpoint.createContract(
                "/api/v1/projects/{projectId}/log-sources", HttpMethod.POST,
                AgentLogSourceRequest.class, AgentLogSourceResponse.class,
                (WiremockDefault) context -> context
                        .matchesJson("agent-create-log-source-request.json")
                        .responseStatus(HttpStatus.CREATED.value())
                        .responseBody("agent-create-log-source-response.json"));
    }

    public static Endpoint<Void, AgentLogSourceResponse[]> listLogSources() {
        return Endpoint.createContract(
                "/api/v1/projects/{projectId}/log-sources", HttpMethod.GET,
                Void.class, AgentLogSourceResponse[].class,
                (WiremockDefault) context -> context.plainUrl()
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("agent-list-log-sources-response.json"));
    }

    public static Endpoint<AgentLogDiscoveryRequest, AgentLogTargetCandidateResponse[]> discoverLogs() {
        return Endpoint.createContract(
                "/api/v1/projects/{projectId}/log-sources/discover", HttpMethod.POST,
                AgentLogDiscoveryRequest.class, AgentLogTargetCandidateResponse[].class,
                (WiremockDefault) context -> context
                        .matchesJson("agent-discover-logs-request.json")
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("agent-discover-logs-response.json"));
    }

    public static Endpoint<RuntimeTargetDiscoveryRequest, RuntimeTargetCandidateResponse[]> discoverRuntimeTargets() {
        return Endpoint.createContract(
                "/api/v1/projects/{projectId}/runtime-targets/discover", HttpMethod.POST,
                RuntimeTargetDiscoveryRequest.class, RuntimeTargetCandidateResponse[].class,
                (WiremockDefault) context -> context
                        .matchesJson("agent-discover-runtime-targets-request.json")
                        .responseStatus(HttpStatus.OK.value())
                        .responseBody("agent-discover-runtime-targets-response.json"));
    }

    public static Endpoint<RuntimeTargetDiscoveryRequest, InfrastructureErrorResponse> discoverRuntimeTargetsConflict() {
        return Endpoint.createContract(
                "/api/v1/projects/{projectId}/runtime-targets/discover", HttpMethod.POST,
                RuntimeTargetDiscoveryRequest.class, InfrastructureErrorResponse.class,
                (WiremockDefault) context -> context
                        .matchesJson("agent-discover-runtime-targets-request.json")
                        .responseStatus(HttpStatus.CONFLICT.value())
                        .responseBody("agent-upstream-error-response.json"));
    }

    public static Endpoint<AgentLogSourceRequest, InfrastructureErrorResponse> createLogSourceConflict() {
        return Endpoint.createContract(
                "/api/v1/projects/{projectId}/log-sources", HttpMethod.POST,
                AgentLogSourceRequest.class, InfrastructureErrorResponse.class,
                (WiremockDefault) context -> context
                        .matchesJson("agent-create-log-source-request.json")
                        .responseStatus(HttpStatus.CONFLICT.value())
                        .responseBody("agent-upstream-error-response.json"));
    }

    public static Endpoint<AgentSshConnectionRequest, Void> testSshConnection() {
        return Endpoint.createContract(
                "/api/v1/projects/{projectId}/ssh-connections/test", HttpMethod.POST,
                AgentSshConnectionRequest.class, Void.class,
                (WiremockDefault) context -> context
                        .matchesJson("agent-test-ssh-connection-request.json")
                        .responseStatus(HttpStatus.NO_CONTENT.value()));
    }

    public static Endpoint<AgentSshConnectionRequest, InfrastructureErrorResponse>
            testSshConnectionFailure() {
        return Endpoint.createContract(
                "/api/v1/projects/{projectId}/ssh-connections/test", HttpMethod.POST,
                AgentSshConnectionRequest.class, InfrastructureErrorResponse.class,
                (WiremockDefault) context -> context
                        .matchesJson("agent-test-ssh-connection-request.json")
                        .responseStatus(HttpStatus.BAD_GATEWAY.value())
                        .responseBody("agent-upstream-error-response.json"));
    }

    private static <Response> Endpoint<AgentProjectRequest, Response> upstreamPost(
            final Class<Response> responseType,
            final HttpStatus status,
            final String responseFixture
    ) {
        return Endpoint.createContract(
                "/api/v1/projects",
                HttpMethod.POST,
                AgentProjectRequest.class,
                responseType,
                (WiremockDefault) context -> context
                        .plainUrl()
                        .matchesJson("agent-create-project-request.json")
                        .responseStatus(status.value())
                        .responseBody(responseFixture)
        );
    }
}
