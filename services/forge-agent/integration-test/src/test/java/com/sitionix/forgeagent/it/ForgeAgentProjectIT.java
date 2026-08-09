package com.sitionix.forgeagent.it;

import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;

import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createProject;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createProjectError;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.listProjects;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ForgeAgentProjectIT {

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @Autowired
    private Environment environment;

    @Test
    void givenProjectRequest_whenCreateProject_thenProjectIsPersisted() {
        assertThat(this.environment.getProperty("spring.application.name")).isEqualTo("forge-agent");
        assertThat(this.environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
        assertThat(this.environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(this.environment.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();

        this.forgeIt.mockMvc()
                .ping(createProject())
                .withRequest("requestCreateProject.json")
                .expectStatus(HttpStatus.CREATED)
                .expectResponse("responseCreateProject.json", "id", "createdAt", "updatedAt")
                .assertAndCreate();

        this.forgeIt.postgresql()
                .get(ProjectEntity.class)
                .singleElement()
                .andExpected(entity -> entity.getId() != null)
                .andExpected(entity -> "Sitionix".equals(entity.getName()))
                .assertEntity();
    }

    @Test
    void givenProjects_whenListProjects_thenProjectsAreOrderedByNormalizedName() {
        this.forgeIt.postgresql()
                .create()
                .to(PROJECT.withJson("project_beta.json"))
                .to(PROJECT.withJson("project_alpha.json"))
                .build();

        this.forgeIt.mockMvc()
                .ping(listProjects())
                .expectStatus(HttpStatus.OK)
                .expectResponse("responseListProjects.json")
                .assertAndCreate();

        assertThat(this.forgeIt.postgresql().get(ProjectEntity.class).getAll())
                .extracting(ProjectEntity::getName)
                .containsExactlyInAnyOrder("Alpha", "Beta");
    }

    @Test
    void givenDuplicateNormalizedProjectName_whenCreateProject_thenConflictIsReturned() {
        this.forgeIt.mockMvc()
                .ping(createProject())
                .withRequest("requestCreateProject.json")
                .expectStatus(HttpStatus.CREATED)
                .assertAndCreate();

        this.forgeIt.mockMvc()
                .ping(createProjectError())
                .withRequest("requestCreateProjectLowercase.json")
                .expectStatus(HttpStatus.CONFLICT)
                .expectResponse("responseDuplicateProjectError.json")
                .assertAndCreate();
    }
}
