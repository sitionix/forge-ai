package com.sitionix.forgeagent.it;

import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDefinitionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDependencyEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDependencyId;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.domain.contract.graph.DbGraphChain;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.Set;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_B_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_C_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.OTHER_PROJECT_AGENT_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_ALPHA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.PROJECT_BETA_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.TARGET_AGENT_ID;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createAgent;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createAgentError;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.getAgent;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.updateAgent;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.updateAgentError;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEPENDENCY;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ForgeAgentDependencyIT {

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @Test
    void givenAgentDependsOnExistingProjectAgent_whenCreateAgent_thenDependencyIsPersisted() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(AGENT_DEFINITION.withJson("agent_a.json"))
                .build();

        this.forgeIt.mockMvc()
                .ping(createAgent())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateAgentDependsOnArchitect.json")
                .expectStatus(HttpStatus.CREATED)
                .expectResponse("responseCreateAgentWithDependency.json", "id", "createdAt", "updatedAt")
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(AgentDependencyEntity.class).getAll())
                .singleElement()
                .satisfies(edge -> assertThat(edge.getId().getDependsOnAgentId()).isEqualTo(AGENT_A_ID));
    }

    @Test
    void givenSelfDependency_whenUpdateAgent_thenBadRequestIsReturned() {
        this.seedProjectWithAgents("agent_a.json");

        this.forgeIt.mockMvc()
                .ping(updateAgentError())
                .withPathParameters(PathParams.create().add("agentId", AGENT_A_ID))
                .withRequest("requestUpdateSelfDependency.json")
                .expectStatus(HttpStatus.BAD_REQUEST)
                .expectResponse("responseSelfDependencyError.json")
                .assertAndCreate();
    }

    @Test
    void givenUnknownDependency_whenCreateAgent_thenBadRequestIsReturned() {
        this.seedProjectWithAgents();

        this.forgeIt.mockMvc()
                .ping(createAgentError())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateUnknownDependencyAgent.json")
                .expectStatus(HttpStatus.BAD_REQUEST)
                .expectResponse("responseUnknownDependencyError.json")
                .assertAndCreate();
    }

    @Test
    void givenCrossProjectDependency_whenCreateAgent_thenConflictIsReturned() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(PROJECT.withJson("project_beta.json"))
                .to(AGENT_DEFINITION.withJson("other_project_agent.json"))
                .build();

        this.forgeIt.mockMvc()
                .ping(createAgentError())
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestCreateCrossProjectDependencyAgent.json")
                .expectStatus(HttpStatus.CONFLICT)
                .expectResponse("responseCrossProjectDependencyError.json")
                .assertAndCreate();

        assertThat(OTHER_PROJECT_AGENT_ID).isNotNull();
        assertThat(PROJECT_BETA_ID).isNotNull();
    }

    @Test
    void givenIndirectCycle_whenUpdateAgent_thenConflictIsReturned() {
        this.seedProjectWithAgents("agent_a.json", "agent_b.json", "agent_c.json");
        this.seedDependency(AGENT_B_ID, AGENT_A_ID);
        this.seedDependency(AGENT_C_ID, AGENT_B_ID);

        this.forgeIt.mockMvc()
                .ping(updateAgentError())
                .withPathParameters(PathParams.create().add("agentId", AGENT_A_ID))
                .withRequest("requestUpdateAgentADependsOnC.json")
                .expectStatus(HttpStatus.CONFLICT)
                .expectResponse("responseDependencyCycleError.json")
                .assertAndCreate();
    }

    @Test
    void givenFailedCyclicUpdate_whenReloadingFromPostgres_thenPreviousAgentAndDependenciesRemain() {
        this.seedProjectWithAgents("agent_a.json", "agent_b.json", "target_agent.json");
        this.seedDependency(TARGET_AGENT_ID, AGENT_A_ID);

        this.forgeIt.mockMvc()
                .ping(updateAgent())
                .withPathParameters(PathParams.create().add("agentId", TARGET_AGENT_ID))
                .withRequest("requestUpdateTargetDependsOnB.json")
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseUpdateTargetDependsOnB.json", "updatedAt")
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(updateAgentError())
                .withPathParameters(PathParams.create().add("agentId", AGENT_B_ID))
                .withRequest("requestUpdateDependencyBCreatesCycle.json")
                .expectStatus(HttpStatus.CONFLICT)
                .expectResponse("responseDependencyCycleError.json")
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(getAgent())
                .withPathParameters(PathParams.create().add("agentId", AGENT_B_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseGetAgentB.json")
                .assertAndCreate();
        this.forgeIt.mockMvc()
                .ping(getAgent())
                .withPathParameters(PathParams.create().add("agentId", TARGET_AGENT_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseGetTargetDependsOnB.json", "updatedAt")
                .assertAndCreate();
        final Set<AgentDependencyId> persistedEdges = this.forgeIt.postgresql()
                .get(AgentDependencyEntity.class)
                .getAll()
                .stream()
                .map(AgentDependencyEntity::getId)
                .collect(java.util.stream.Collectors.toSet());

        this.forgeIt.postgresql()
                .get(AgentDefinitionEntity.class)
                .where(entity -> entity.getId().equals(AGENT_B_ID))
                .singleElement()
                .andExpected(entity -> "Agent B".equals(entity.getName()))
                .andExpected(entity -> !entity.getOutputSchema().contains("changed"))
                .assertEntity();
        assertThat(persistedEdges).containsExactly(new AgentDependencyId(TARGET_AGENT_ID, AGENT_B_ID));
    }

    @Test
    void givenUnchangedDependency_whenUpdateAgent_thenDependencyEdgeIsPreserved() {
        this.seedProjectWithAgents("agent_b.json", "target_agent.json");
        this.seedDependency(TARGET_AGENT_ID, AGENT_B_ID);

        this.forgeIt.mockMvc()
                .ping(updateAgent())
                .withPathParameters(PathParams.create().add("agentId", TARGET_AGENT_ID))
                .withRequest("requestUpdateTargetDependsOnB.json")
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseUpdateTargetDependsOnB.json", "updatedAt")
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(getAgent())
                .withPathParameters(PathParams.create().add("agentId", TARGET_AGENT_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseGetTargetDependsOnB.json", "updatedAt")
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(AgentDependencyEntity.class).getAll())
                .extracting(AgentDependencyEntity::getId)
                .containsExactly(new AgentDependencyId(TARGET_AGENT_ID, AGENT_B_ID));
    }

    private void seedProjectWithAgents(final String... agentFixtures) {
        DbGraphChain<?> builder = this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"));
        for (final String fixture : agentFixtures) {
            builder = builder.to(AGENT_DEFINITION.withJson(fixture));
        }
        builder.build();
    }

    private void seedDependency(final java.util.UUID agentId, final java.util.UUID dependsOnAgentId) {
        this.forgeIt.postgresql()
                .create()
                .to(AGENT_DEPENDENCY.withEntity(new AgentDependencyEntity(new AgentDependencyId(agentId, dependsOnAgentId))))
                .build();
    }
}
