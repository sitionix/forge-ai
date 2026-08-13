package com.sitionix.forgeai.it;

import static com.sitionix.forgeai.it.ForgeAgentProxyFixtures.PROJECT_ID;
import static com.sitionix.forgeai.it.ForgeAgentProxyFixtures.TASK_ID;
import static com.sitionix.forgeit.wiremock.api.Parameter.equalTo;

import com.sitionix.forgeai.it.infra.ForgeAgentProxyMockMvcEndpoint;
import com.sitionix.forgeai.it.infra.ForgeAgentProxyWireMockEndpoint;
import com.sitionix.forgeai.it.infra.ProxyTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import com.sitionix.forgeit.wiremock.api.WireMockPathParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

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
    void givenProjectId_whenListTasks_thenPathIsForwarded() {
        final var mapping = this.testManager.wiremock()
                .createMapping(ForgeAgentProxyWireMockEndpoint.listProjectTasks())
                .pathPattern(projectWireMockPathParams())
                .createDefault();

        this.testManager.mockMvc()
                .ping(ForgeAgentProxyMockMvcEndpoint.listProjectTasks())
                .withPathParameters(projectMockMvcPathParams())
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
}
