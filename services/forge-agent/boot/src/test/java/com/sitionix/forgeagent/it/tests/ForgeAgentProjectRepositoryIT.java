package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.DELETE_PROJECT;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.IMPORT_PROJECT_REPOSITORY;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.IMPORT_PROJECT_REPOSITORY_ERROR;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.LIST_PROJECT_REPOSITORIES;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectRepositoryEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@IntegrationTest
class ForgeAgentProjectRepositoryIT {

    private static final UUID PROJECT_ALPHA_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @Test
    void givenProject_whenImportRepositories_thenRepositoriesArePersistedAndListed() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportProjectRepository.json")
                .expectStatus(HttpStatus.CREATED)
                .expectResponse("responseImportProjectRepository.json", "id", "createdAt")
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportSecondProjectRepository.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll())
                .extracting(ProjectRepositoryEntity::getRemoteUrl)
                .containsExactlyInAnyOrder(
                        "git@gitlab.com:company/service-a.git",
                        "https://github.com/company/service-b.git"
                );

        this.forgeIt.mockMvc()
                .ping(LIST_PROJECT_REPOSITORIES)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseListProjectRepositories.json", "id", "createdAt")
                .assertAndCreate();
    }

    @Test
    void givenBlankRemoteUrl_whenImportRepository_thenValidationErrorIsReturned() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY_ERROR)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportBlankProjectRepository.json")
                .expectStatus(HttpStatus.BAD_REQUEST)
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll()).isEmpty();
    }

    @Test
    void givenProjectWithRepositories_whenDeleteProject_thenRepositoriesAreDeletedByCascade() {
        this.seedProject();

        this.forgeIt.mockMvc()
                .ping(IMPORT_PROJECT_REPOSITORY)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .withRequest("requestImportProjectRepository.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(DELETE_PROJECT)
                .withPathParameters(PathParams.create().add("projectId", PROJECT_ALPHA_ID))
                .expectStatus(HttpStatus.NO_CONTENT)
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectRepositoryEntity.class).getAll()).isEmpty();
    }

    private void seedProject() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_alpha.json"))
                .build();
    }
}
