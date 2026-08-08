package com.sitionix.forgeagent.it;

import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createProject;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.createProjectError;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.listProjects;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@IntegrationTest
class ForgeAgentProjectIT {

    @Autowired
    private ForgeAgentTestManager forgeIt;

    @Test
    void givenProjectRequest_whenCreateProject_thenProjectIsPersisted() {
        this.forgeIt.mockMvc()
                .ping(createProject())
                .withRequest("requestCreateProject.json")
                .expectStatus(HttpStatus.CREATED)
                .andExpectPath(jsonPath("$.name").value("Sitionix"))
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
                .andExpectPath(jsonPath("$[0].name").value("Alpha"))
                .andExpectPath(jsonPath("$[1].name").value("Beta"))
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
                .andExpectPath(jsonPath("$.code").value("DUPLICATE_PROJECT_NAME"))
                .assertAndCreate();
    }
}
