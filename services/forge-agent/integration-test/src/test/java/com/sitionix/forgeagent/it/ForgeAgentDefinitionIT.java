package com.sitionix.forgeagent.it;

import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDefinitionEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_BETA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_GAMMA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.UNKNOWN_AGENT_ID;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createAgent;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createAgentError;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.getAgent;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.getAgentError;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.listProjectAgents;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.listProjectAgentsError;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.updateAgent;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ForgeAgentDefinitionIT {

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @Test
    void givenAgentRequest_whenCreateListGetAndUpdateAgent_thenJsonbSchemaRoundTrips() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .build();

        this.forgeIt.mockMvc()
                .ping(createAgent())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateAgent.json")
                .expectStatus(HttpStatus.CREATED)
                .expectResponse("responseCreateAgent.json", "id", "createdAt", "updatedAt")
                .assertAndCreate();

        final AgentDefinitionEntity created = this.forgeIt.postgresql()
                .get(AgentDefinitionEntity.class)
                .singleElement()
                .andExpected(entity -> "Analyzer".equals(entity.getName()))
                .andExpected(entity -> entity.getOutputSchema().contains("\"type\""))
                .andExpected(entity -> entity.getOutputSchema().contains("\"object\""))
                .assertEntity();

        this.forgeIt.mockMvc()
                .ping(listProjectAgents())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseListAgents.json", "id", "createdAt", "updatedAt")
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(getAgent())
                .withPathParameters(PathParams.create().add("agentId", created.getId()))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseGetAgent.json", "id", "createdAt", "updatedAt")
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(updateAgent())
                .withPathParameters(PathParams.create().add("agentId", created.getId()))
                .withRequest("requestUpdateAgent.json")
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseUpdateAgent.json", "id", "createdAt", "updatedAt")
                .assertAndCreate();

        this.forgeIt.postgresql()
                .get(AgentDefinitionEntity.class)
                .singleElement()
                .andExpected(entity -> "Analyzer Updated".equals(entity.getName()))
                .andExpected(entity -> entity.getOutputSchema().contains("\"updated\""))
                .assertEntity();
    }

    @Test
    void givenDuplicateAgentNameInSameProject_whenCreateAgent_thenConflictIsReturned() {
        this.seedTwoProjects();
        this.forgeIt.mockMvc()
                .ping(createAgent())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateAgent.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(createAgentError())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateAgentLowercase.json")
                .expectStatus(HttpStatus.CONFLICT)
                .expectResponse("responseDuplicateAgentError.json")
                .assertAndCreate();
        this.forgeIt.mockMvc()
                .ping(createAgent())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_BETA_ID))
                .withRequest("requestCreateAgent.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(AgentDefinitionEntity.class).getAll())
                .extracting(AgentDefinitionEntity::getName)
                .containsExactlyInAnyOrder("Analyzer", "Analyzer");
    }

    @Test
    void givenMissingProject_whenListProjectAgents_thenProjectNotFoundIsReturned() {
        this.forgeIt.mockMvc()
                .ping(listProjectAgentsError())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_GAMMA_ID))
                .expectStatus(HttpStatus.NOT_FOUND)
                .expectResponse("responseProjectNotFoundError.json")
                .assertAndCreate();
    }

    @Test
    void givenMissingAgent_whenGetAgent_thenAgentNotFoundIsReturned() {
        this.forgeIt.mockMvc()
                .ping(getAgentError())
                .withPathParameters(PathParams.create().add("agentId", UNKNOWN_AGENT_ID))
                .expectStatus(HttpStatus.NOT_FOUND)
                .expectResponse("responseAgentNotFoundError.json")
                .assertAndCreate();
    }

    private void seedTwoProjects() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(PROJECT.withJson("project_beta.json"))
                .build();
    }
}
