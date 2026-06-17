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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

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
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("COMPLETED"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.sourceCount").value(1))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.skippedBreakdown.total").exists())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.skippedBreakdown.byReason").exists())
                .assertDefault();

        this.testManager.sqlite()
                .get(KnowledgeInventoryBuildEntity.class)
                .hasSize(1)
                .singleElement()
                .andExpected(build -> "COMPLETED".equals(build.getStatus()))
                .andExpected(build -> Integer.valueOf(1).equals(build.getSourceCount()))
                .andExpected(build -> build.getFileCount() != null && build.getFileCount() > 0)
                .andExpected(build -> build.getSkippedReasonsJson() != null && build.getSkippedReasonsJson().contains("\"total\""))
                .assertEntity();

        this.testManager.sqlite()
                .get(KnowledgeSourceEntity.class)
                .hasSize(1)
                .singleElement()
                .andExpected(source -> "forge-ai".equals(source.getSourceId()))
                .andExpected(source -> "Forge AI Service SOX".equals(source.getDisplayName()))
                .andExpected(source -> "backend".equals(source.getGroupName()))
                .assertEntity();

        this.testManager.sqlite()
                .get(KnowledgeFileEntity.class)
                .andExpected(file -> "forge-ai".equals(file.getSourceId()))
                .andExpected(file -> file.getRelativePath() != null && file.getRelativePath().endsWith(".java"))
                .andExpected(file -> file.getContentHash() != null && !file.getContentHash().isBlank())
                .anyMatch();
    }

    @Test
    @DisplayName("Should expose Knowledge inventory status with persisted skipped breakdown")
    void givenSeededInventory_whenGetInventoryStatus_thenReturnSkippedBreakdownFromSqlite() throws Exception {
        this.testManager.sqlite()
                .create()
                .to(INVENTORY_BUILD)
                .to(KNOWLEDGE_SOURCE)
                .to(KNOWLEDGE_FILE)
                .build();

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.knowledgeInventoryStatus())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("READY"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.sourceCount").value(1))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.fileCount").value(1))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.skippedCount").value(0))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.skippedBreakdown.total").value(0))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.skippedBreakdown.byReason").isMap())
                .assertDefault();
    }

}
