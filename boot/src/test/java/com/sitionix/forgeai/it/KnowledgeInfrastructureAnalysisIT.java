package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisStopView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGateway;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class KnowledgeInfrastructureAnalysisIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private KnowledgeGateway knowledgeGateway;

    @Test
    @DisplayName("Should proxy Knowledge analysis stop through Forge infrastructure API")
    void givenRunningKnowledgeAnalysisJob_whenStopAnalysis_thenReturnStopRequested() throws Exception {
        final String jobId = "3483cd96-37f6-4156-826e-59fc4320d826";
        when(this.knowledgeGateway.stopAnalysis(jobId)).thenReturn(new KnowledgeAnalysisStopView(
                jobId,
                "STOP_REQUESTED",
                "Knowledge analysis stop requested"
        ));

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.knowledgeAnalysisStop())
                .withPathParameters(PathParams.create().add("jobId", jobId))
                .andExpectPath(jsonPath("$.jobId").value(jobId))
                .andExpectPath(jsonPath("$.status").value("STOP_REQUESTED"))
                .andExpectPath(jsonPath("$.message").value("Knowledge analysis stop requested"))
                .assertDefault();

        verify(this.knowledgeGateway).stopAnalysis(jobId);
    }
}
