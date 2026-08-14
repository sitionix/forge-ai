package com.sitionix.forgeai.it;

import static com.sitionix.forgeai.it.ForgeAgentProxyFixtures.PROJECT_ID;
import static com.sitionix.forgeai.it.ForgeAgentProxyFixtures.TASK_ID;
import static com.sitionix.forgeit.wiremock.api.Parameter.equalTo;

import com.sitionix.forgeai.it.infra.ForgeAgentProxyMockMvcEndpoint;
import com.sitionix.forgeai.it.infra.ForgeAgentProxyWireMockEndpoint;
import com.sitionix.forgeai.it.infra.ProxyTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.domain.endpoint.Endpoint;
import com.sitionix.forgeit.mockmvc.api.QueryParams;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import com.sitionix.forgeit.wiremock.api.WireMockQueryParams;
import com.sitionix.forgeit.wiremock.api.WireMockPathParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge.ai.infrastructure.agent.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.agent.connect-timeout=5s",
        "forge.ai.infrastructure.agent.read-timeout=5s",
        "forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}",
        "forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ForgeAgentProjectTasksProxyIT extends AbstractForgeAiIT {

    @Autowired
    private ProxyTestManager testManager;

    @Test
    void givenTaskRequest_whenCreateTask_thenBodyAndProjectPathAreForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.createProjectTask())
                .pathPattern(projectWireMockPathParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.createProjectTask())
                .withPathParameters(projectMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenInvalidTaskRequest_whenCreateTask_thenValidationErrorIsReturnedWithoutUpstreamRequest() {
        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.createProjectTaskLocalInvalid())
                .withPathParameters(projectMockMvcPathParams())
                .assertDefault();

        this.assertNoMatchingUpstreamRequest(ForgeAgentProxyWireMockEndpoint.createProjectTask());
    }

    @Test
    void givenEmptyWorkflowUpstreamError_whenCreateTask_thenControlledErrorIsForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.createProjectTaskEmptyWorkflow())
                .pathPattern(projectWireMockPathParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.createProjectTaskEmptyWorkflow())
                .withPathParameters(projectMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenProjectId_whenListTasks_thenPathAndPaginationAreForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.listProjectTasks())
                .pathPattern(projectWireMockPathParams())
                .urlWithQueryParam(taskPageWireMockQueryParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.listProjectTasks())
                .withPathParameters(projectMockMvcPathParams())
                .withQueryParameters(taskPageMockMvcQueryParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenTaskId_whenGetTask_thenPathIsForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.getProjectTask())
                .pathPattern(taskWireMockPathParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.getProjectTask())
                .withPathParameters(taskMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    @Test
    void givenTaskId_whenDeleteTask_thenDeletePathIsForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.deleteProjectTask())
                .pathPattern(taskWireMockPathParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.deleteProjectTask())
                .withPathParameters(taskMockMvcPathParams())
                .assertDefault();

        mapping.verify();
    }

    private static PathParams projectMockMvcPathParams() {
        return PathParams.create().add("projectId", PROJECT_ID);
    }

    private static PathParams taskMockMvcPathParams() {
        return PathParams.create().add("taskId", TASK_ID);
    }

    private static WireMockPathParams projectWireMockPathParams() {
        return WireMockPathParams.create().add("projectId", equalTo(PROJECT_ID.toString()));
    }

    private static WireMockPathParams taskWireMockPathParams() {
        return WireMockPathParams.create().add("taskId", equalTo(TASK_ID.toString()));
    }

    private static QueryParams taskPageMockMvcQueryParams() {
        return QueryParams.create()
                .add("page", "2")
                .add("size", "10");
    }

    private static WireMockQueryParams taskPageWireMockQueryParams() {
        return WireMockQueryParams.create()
                .add("page", equalTo("2"))
                .add("size", equalTo("10"));
    }

    private void assertNoMatchingUpstreamRequest(final Endpoint<?, ?> endpoint) {
        assertThatThrownBy(() -> this.testManager.wiremock().check(endpoint).verify())
                .isInstanceOf(AssertionError.class);
    }
}
