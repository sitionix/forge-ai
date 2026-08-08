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
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createAgent;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createAgentError;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.getAgent;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.listProjectAgents;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.updateAgent;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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
                .andExpectPath(jsonPath("$.name").value("Analyzer"))
                .andExpectPath(jsonPath("$.outputSchema.type").value("object"))
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
                .andExpectPath(jsonPath("$[0].name").value("Analyzer"))
                .andExpectPath(jsonPath("$[0].dependsOn").isEmpty())
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(getAgent())
                .withPathParameters(PathParams.create().add("agentId", created.getId()))
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$.instructions").value("Analyze project context."))
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(updateAgent())
                .withPathParameters(PathParams.create().add("agentId", created.getId()))
                .withRequest("requestUpdateAgent.json")
                .expectStatus(HttpStatus.OK)
                .andExpectPath(jsonPath("$.name").value("Analyzer Updated"))
                .andExpectPath(jsonPath("$.outputSchema.properties.updated.type").value("boolean"))
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
                .andExpectPath(jsonPath("$.code").value("DUPLICATE_AGENT_NAME"))
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

    private void seedTwoProjects() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(PROJECT.withJson("project_beta.json"))
                .build();
    }
}
