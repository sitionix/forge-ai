package com.sitionix.forgeagent.it;

import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDependencyEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.AgentDependencyId;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_A_ID;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.AGENT_B_ID;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.updateAgentUntyped;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.AGENT_DEFINITION;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ForgeAgentConcurrencyIT {

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @Test
    void givenConcurrentInverseDependencyUpdates_whenBothCommitAttempted_thenOnlyOneEdgePersists() throws Exception {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .to(AGENT_DEFINITION.withJson("agent_a.json"))
                .to(AGENT_DEFINITION.withJson("agent_b.json"))
                .build();
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CyclicBarrier barrier = new CyclicBarrier(3);
        final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();
        final Queue<String> conflictBodies = new ConcurrentLinkedQueue<>();

        final Callable<Void> updateAToDependOnB = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            this.forgeIt.mockMvc()
                    .ping(updateAgentUntyped())
                    .withPathParameters(PathParams.create().add("agentId", AGENT_A_ID))
                    .withRequest("requestUpdateConcurrentADependsOnB.json")
                    .andExpectPath(result -> {
                        statuses.add(result.getResponse().getStatus());
                        if (result.getResponse().getStatus() == HttpStatus.CONFLICT.value()) {
                            conflictBodies.add(result.getResponse().getContentAsString());
                        }
                    })
                    .assertAndCreate();
            return null;
        };
        final Callable<Void> updateBToDependOnA = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            this.forgeIt.mockMvc()
                    .ping(updateAgentUntyped())
                    .withPathParameters(PathParams.create().add("agentId", AGENT_B_ID))
                    .withRequest("requestUpdateConcurrentBDependsOnA.json")
                    .andExpectPath(result -> {
                        statuses.add(result.getResponse().getStatus());
                        if (result.getResponse().getStatus() == HttpStatus.CONFLICT.value()) {
                            conflictBodies.add(result.getResponse().getContentAsString());
                        }
                    })
                    .assertAndCreate();
            return null;
        };

        try {
            final Future<Void> first = executor.submit(updateAToDependOnB);
            final Future<Void> second = executor.submit(updateBToDependOnA);
            barrier.await(10, TimeUnit.SECONDS);
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(statuses)
                .containsExactlyInAnyOrder(HttpStatus.OK.value(), HttpStatus.CONFLICT.value());
        assertThat(conflictBodies).singleElement()
                .asString()
                .contains("\"code\":\"DEPENDENCY_GRAPH_CYCLE\"");

        final Set<AgentDependencyId> persistedEdges = this.forgeIt.postgresql()
                .get(AgentDependencyEntity.class)
                .getAll()
                .stream()
                .map(AgentDependencyEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        final boolean aDependsOnB = persistedEdges.contains(new AgentDependencyId(AGENT_A_ID, AGENT_B_ID));
        final boolean bDependsOnA = persistedEdges.contains(new AgentDependencyId(AGENT_B_ID, AGENT_A_ID));

        assertThat(aDependsOnB && bDependsOnA).isFalse();
        assertThat(List.of(aDependsOnB, bDependsOnA).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    }
}
