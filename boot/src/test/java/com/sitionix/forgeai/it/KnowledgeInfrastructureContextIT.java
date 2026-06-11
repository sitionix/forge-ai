package com.sitionix.forgeai.it;

import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.ForgeItSqliteEntityManagerConfiguration;
import com.sitionix.forgeai.it.infra.KnowledgeTestManager;
import com.sitionix.forgeai.it.knowledge.KnowledgeFileEntity;
import com.sitionix.forgeai.it.knowledge.KnowledgeInventoryBuildEntity;
import com.sitionix.forgeai.it.knowledge.KnowledgeSourceEntity;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.domain.contract.DbContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import static com.sitionix.forgeit.domain.contract.DbContractsDsl.entity;

@Import(ForgeItSqliteEntityManagerConfiguration.class)
@IntegrationTest
@ActiveProfiles({"it", "knowledge-it"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class KnowledgeInfrastructureContextIT extends AbstractForgeAiIT {

    private static final DbContract<KnowledgeInventoryBuildEntity> INVENTORY_BUILD =
            entity(KnowledgeInventoryBuildEntity.class).withDefaultBody("knowledgeInventoryCompletedBuild.json").build();

    private static final DbContract<KnowledgeSourceEntity> KNOWLEDGE_SOURCE =
            entity(KnowledgeSourceEntity.class).withDefaultBody("knowledgeForgeAiSource.json").build();

    private static final DbContract<KnowledgeFileEntity> KNOWLEDGE_FILE =
            entity(KnowledgeFileEntity.class).withDefaultBody("knowledgeJarvisGatewayFile.json").build();

    @Autowired
    private KnowledgeTestManager testManager;

    @Test
    @DisplayName("Should build Knowledge inventory through controller and persist SQLite rows")
    void givenCatalogSource_whenBuildInventory_thenPersistInventoryInSqlite() throws Exception {
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.knowledgeInventoryBuild())
                .andExpectPath(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("COMPLETED"))
                .andExpectPath(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sourceCount").value(1))
                .assertDefault();

        this.testManager.sqlite()
                .get(KnowledgeInventoryBuildEntity.class)
                .hasSize(1)
                .singleElement()
                .andExpected(build -> "COMPLETED".equals(build.getStatus()))
                .andExpected(build -> Integer.valueOf(1).equals(build.getSourceCount()))
                .andExpected(build -> build.getFileCount() != null && build.getFileCount() > 0)
                .assertEntity();

        this.testManager.sqlite()
                .get(KnowledgeSourceEntity.class)
                .hasSize(1)
                .singleElement()
                .andExpected(source -> "forge-ai".equals(source.getSourceId()))
                .assertEntity();
    }

    @Test
    @DisplayName("Should expose Knowledge context through Forge infrastructure API")
    void givenValidContextRequest_whenPostContext_thenReturnContextBundleFromSqliteInventory() throws Exception {
        seedKnowledgeInventory();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.knowledgeContext())
                .assertDefault();

        this.testManager.sqlite()
                .get(KnowledgeSourceEntity.class)
                .hasSize(1)
                .singleElement()
                .andExpected(source -> "forge-ai".equals(source.getSourceId()))
                .assertEntity();

        this.testManager.sqlite()
                .get(KnowledgeFileEntity.class)
                .hasSize(1)
                .singleElement()
                .andExpected(file -> "forge-ai".equals(file.getSourceId()))
                .andExpected(file -> file.getRelativePath().endsWith("JarvisGateway.java"))
                .andExpected(file -> "jarvis-gateway-hash".equals(file.getContentHash()))
                .assertEntity();
    }

    @Test
    @DisplayName("Should reject blank Knowledge context query before gateway")
    void givenBlankContextQuery_whenPostContext_thenReturnBadRequest() throws Exception {
        seedKnowledgeInventory();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.knowledgeContext())
                .withRequest("requestKnowledgeContextBlank.json")
                .expectStatus(HttpStatus.BAD_REQUEST)
                .expectResponse("responseKnowledgeContextBlank.json")
                .andExpectPath(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("CONTEXT_QUERY_INVALID"))
                .andExpectPath(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Context query must not be empty"))
                .assertDefault();

        this.testManager.sqlite()
                .get(KnowledgeFileEntity.class)
                .hasSize(1)
                .singleElement()
                .andExpected(file -> "jarvis-gateway-hash".equals(file.getContentHash()))
                .assertEntity();
    }

    private void seedKnowledgeInventory() {
        this.testManager.sqlite()
                .create()
                .to(INVENTORY_BUILD)
                .to(KNOWLEDGE_SOURCE)
                .to(KNOWLEDGE_FILE)
                .build();
    }
}
