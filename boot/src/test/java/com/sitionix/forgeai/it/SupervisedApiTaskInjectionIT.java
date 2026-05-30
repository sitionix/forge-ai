package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ItCodexSessionRepositoryStub;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge.ai.supervised-execution.enabled=true",
        "forge.ai.supervised-execution.agents[0]=api"
})
class SupervisedApiTaskInjectionIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @Autowired
    private ItCodexSessionRepositoryStub codexSessionRepositoryStub;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @MockBean
    private CodexClient codexClient;

    @Test
    @DisplayName("Should inject API input tasks into supervised step prompt")
    void givenReadyApiLaneWithInputTask_whenSupervisorEnabled_thenStepPromptContainsTaskPayload() {
        // given
        this.codexSessionRepositoryStub.clearSentMessages();
        this.testManager.mongo().create(TicketDocument.class).body("readyToStartApiOnlyWithInputTaskSeedTicket.json");
        this.testManager.mongo().create(AgentTicketDocument.class).body("readyToStartApiOnlyWithInputTaskApiTicket.json");

        // when
        this.readyToStartLaneJob.run();

        // then
        assertThat(this.codexSessionRepositoryStub.sentMessages())
                .anyMatch(message -> message.contains("Task payloads for this lane:")
                        && message.contains("\"scope\":\"backendforfrontendservice-sox\"")
                        && message.contains("\"summary\":\"Add authenticated flow and palette endpoints.\""));
        verifyNoInteractions(this.codexClient);
    }
}
