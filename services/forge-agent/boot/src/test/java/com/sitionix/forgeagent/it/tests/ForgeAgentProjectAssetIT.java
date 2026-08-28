package com.sitionix.forgeagent.it.tests;

import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.*;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.LogSourceResponse;
import com.sitionix.forgeagent.infrastructure.postgres.entity.LogSourceEntity;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectAssetEntity;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ForgeAgentProjectAssetIT {
    private static final UUID PROJECT_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_PROJECT_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");

    @Autowired
    private ForgeAgentTestManager forgeIt;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void assetRestVerticalUsesPersistenceDeterministicInspectionAndCleansMonitoring() throws Exception {
        this.forgeIt.postgresql().create()
                .to(PROJECT.withJson("logs_project.json"))
                .to(PROJECT.withJson("project_beta.json"))
                .to(SSH_CONNECTION.withJson("logs_ssh.json"))
                .build();

        this.forgeIt.mockMvc().ping(CREATE_PROJECT_ASSET)
                .withPathParameters(project(PROJECT_ID))
                .withRequest("requestCreateProjectAsset.json")
                .expectStatus(HttpStatus.CREATED).assertAndCreate();
        final ProjectAssetEntity asset = this.forgeIt.postgresql().get(ProjectAssetEntity.class)
                .getAll().stream().findFirst().orElseThrow();

        this.forgeIt.mockMvc().ping(LIST_PROJECT_ASSETS)
                .withPathParameters(project(PROJECT_ID)).expectStatus(HttpStatus.OK).assertAndCreate();
        this.forgeIt.mockMvc().ping(GET_PROJECT_ASSET)
                .withPathParameters(asset(PROJECT_ID, asset.getId()))
                .expectStatus(HttpStatus.OK).assertAndCreate();
        this.forgeIt.mockMvc().ping(GET_PROJECT_ASSET_ERROR)
                .withPathParameters(asset(OTHER_PROJECT_ID, asset.getId()))
                .expectStatus(HttpStatus.NOT_FOUND).assertAndCreate();
        this.forgeIt.mockMvc().ping(CREATE_PROJECT_ASSET_ERROR)
                .withPathParameters(project(OTHER_PROJECT_ID))
                .withRequest("requestCreateProjectAsset.json")
                .expectStatus(HttpStatus.NOT_FOUND).assertAndCreate();

        this.forgeIt.mockMvc().ping(GET_PROJECT_ASSET_METRICS)
                .withPathParameters(asset(PROJECT_ID, asset.getId()))
                .expectStatus(HttpStatus.OK).expectResponse("responseAssetMetrics.json").assertAndCreate();
        this.forgeIt.mockMvc().ping(GET_PROJECT_ASSET_CAPABILITIES)
                .withPathParameters(asset(PROJECT_ID, asset.getId()))
                .expectStatus(HttpStatus.OK).expectResponse("responseAssetCapabilities.json").assertAndCreate();

        final var monitoringResult = this.mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/assets/{assetId}/monitoring",
                        PROJECT_ID,
                        asset.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"camera.service\",\"provider\":\"SYSTEMD\",\"target\":\"camera.service\",\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn();
        final var monitoringResponse = this.objectMapper.readValue(
                monitoringResult.getResponse().getContentAsString(), LogSourceResponse.class);
        assertThat(monitoringResponse.projectId()).isEqualTo(PROJECT_ID);
        assertThat(monitoringResponse.name()).isEqualTo("camera.service");
        assertThat(monitoringResponse.serviceId()).isNull();
        assertThat(monitoringResponse.assetId()).isEqualTo(asset.getId());
        assertThat(monitoringResponse.connection().name()).isEqualTo("SSH");
        assertThat(monitoringResponse.sshConnectionId()).isNull();
        assertThat(monitoringResponse.provider().name()).isEqualTo("SYSTEMD");
        assertThat(monitoringResponse.enabled()).isTrue();
        final LogSourceEntity monitoring = this.forgeIt.postgresql().get(LogSourceEntity.class)
                .getAll().stream().findFirst().orElseThrow();
        assertThat(monitoring.getAssetId()).isEqualTo(asset.getId());
        assertThat(monitoring.getServiceId()).isNull();
        this.forgeIt.mockMvc().ping(LIST_PROJECT_ASSET_MONITORING)
                .withPathParameters(asset(PROJECT_ID, asset.getId()))
                .expectStatus(HttpStatus.OK).assertAndCreate();

        this.forgeIt.mockMvc().ping(DELETE_PROJECT_ASSET)
                .withPathParameters(asset(PROJECT_ID, asset.getId()))
                .expectStatus(HttpStatus.NO_CONTENT).assertAndCreate();
        assertThat(this.forgeIt.postgresql().get(ProjectAssetEntity.class).getAll()).isEmpty();
        assertThat(this.forgeIt.postgresql().get(LogSourceEntity.class).getAll()).isEmpty();
    }

    private static PathParams project(final UUID projectId) {
        return PathParams.create().add("projectId", projectId);
    }

    private static PathParams asset(final UUID projectId, final UUID assetId) {
        return project(projectId).add("assetId", assetId);
    }
}
