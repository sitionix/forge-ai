package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.*;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.infrastructure.postgres.entity.LogSourceEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectServiceEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@IntegrationTest
class ForgeAgentProjectServiceIT {
    private static final UUID PROJECT_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_PROJECT_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID SEEDED_SERVICE_ID = UUID.fromString("90000000-0000-4000-8000-000000000010");

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @Test
    void serviceCrudRuntimeLogsDeleteSemanticsAndProjectIsolationUseRealRestAndPersistence() {
        this.forgeIt.postgresql().create()
                .to(PROJECT.withJson("logs_project.json"))
                .to(PROJECT.withJson("project_beta.json"))
                .to(PROJECT_SERVICE.withJson("logs_service.json"))
                .build();

        this.forgeIt.mockMvc().ping(CREATE_PROJECT_SERVICE)
                .withPathParameters(project(PROJECT_ID))
                .withRequest("requestCreateProjectService.json")
                .expectStatus(HttpStatus.CREATED).assertAndCreate();
        final ProjectServiceEntity created = this.forgeIt.postgresql().get(ProjectServiceEntity.class)
                .getAll().stream().filter(service -> !SEEDED_SERVICE_ID.equals(service.getId()))
                .findFirst().orElseThrow();

        this.forgeIt.mockMvc().ping(GET_PROJECT_SERVICE)
                .withPathParameters(service(PROJECT_ID, created.getId()))
                .expectStatus(HttpStatus.OK).assertAndCreate();
        this.forgeIt.mockMvc().ping(LIST_PROJECT_SERVICES)
                .withPathParameters(project(PROJECT_ID)).expectStatus(HttpStatus.OK).assertAndCreate();
        this.forgeIt.mockMvc().ping(UPDATE_PROJECT_SERVICE)
                .withPathParameters(service(PROJECT_ID, created.getId()))
                .withRequest("requestUpdateProjectService.json")
                .expectStatus(HttpStatus.OK).assertAndCreate();
        this.forgeIt.mockMvc().ping(GET_PROJECT_SERVICE_RUNTIME)
                .withPathParameters(service(PROJECT_ID, created.getId()))
                .expectStatus(HttpStatus.OK).assertAndCreate();

        this.forgeIt.mockMvc().ping(GET_PROJECT_SERVICE_ERROR)
                .withPathParameters(service(OTHER_PROJECT_ID, created.getId()))
                .expectStatus(HttpStatus.NOT_FOUND).assertAndCreate();

        this.forgeIt.mockMvc().ping(CREATE_LOG_SOURCE)
                .withPathParameters(project(PROJECT_ID))
                .withRequest("requestCreateServiceLogSource.json")
                .expectStatus(HttpStatus.CREATED).assertAndCreate();
        this.forgeIt.mockMvc().ping(LIST_SERVICE_LOG_SOURCES)
                .withPathParameters(service(PROJECT_ID, SEEDED_SERVICE_ID))
                .expectStatus(HttpStatus.OK).assertAndCreate();

        this.forgeIt.mockMvc().ping(DELETE_PROJECT_SERVICE)
                .withPathParameters(service(PROJECT_ID, SEEDED_SERVICE_ID))
                .expectStatus(HttpStatus.NO_CONTENT).assertAndCreate();
        assertThat(this.forgeIt.postgresql().get(LogSourceEntity.class).getAll())
                .singleElement().satisfies(source -> assertThat(source.getServiceId()).isNull());
    }

    private static PathParams project(UUID projectId) {
        return PathParams.create().add("projectId", projectId);
    }

    private static PathParams service(UUID projectId, UUID serviceId) {
        return project(projectId).add("serviceId", serviceId);
    }
}
