package com.sitionix.forgeai.it;

import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class OperatorUiAgentsConfigIT extends AbstractForgeAiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestManager testManager;

    @Test
    @DisplayName("Should expose agent dependencies, lane strategy resources, instructions, and payload contracts for operator UI")
    void givenOperatorUiRequest_whenAgentConfig_thenReturnEditableAgentConfigurationReadModel() throws Exception {
        this.mockMvc.perform(get("/api/v1/forge-ai/operator/ui/agents/config")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[?(@.id == 'analyzer')]").isNotEmpty())
                .andExpect(jsonPath("$.agents[?(@.id == 'analyzer')].laneStrategy.steps").isNotEmpty())
                .andExpect(jsonPath("$.editableResources[?(@.resourceKey == 'agent-yml')]").isNotEmpty())
                .andExpect(jsonPath("$.editableResources[?(@.resourceKey == 'lane-strategies-yml')]").isNotEmpty())
                .andExpect(jsonPath("$.editableResources[?(@.resourceKey == 'contract:ArchitectPayload')]").isNotEmpty())
                .andExpect(jsonPath("$.restartRequiredMessage").value("Changes are written to source resources. Restart Forge AI to apply them to runtime scheduling and supervised execution."));
    }
}
