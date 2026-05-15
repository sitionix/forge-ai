package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@IntegrationTest
class ForgeAiStartFlowIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should build Codex payload and avoid real terminal launch")
    void givenStartForgeRequest_whenStartForge_thenBuildPromptWithSerializedCommandPayload() {
        //given
        when(this.codexCliCommandBuilder.build(anyString())).thenReturn("mock-codex-command");

        //when then
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.startForge())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.id").isNotEmpty())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.createdAt").isNotEmpty())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticket").value("SITIONIX-1"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.task").value("hi"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.scope").value("forgeai"))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("SUBMITTED"))
                .assertDefault();

        verify(this.codexCliCommandBuilder).build(ArgumentMatchers.argThat(prompt ->
                prompt.startsWith("Привіт. Оброби цей запит: ")
                        && prompt.contains("\"ticket\":\"SITIONIX-1\"")
                        && prompt.contains("\"task\":\"hi\"")
                        && prompt.contains("\"serviceIds\":[\"athssox\",\"forgeai\"]")
                        && prompt.contains("\"sourceTerminalTty\":\"/dev/ttys999\"")
                        && prompt.contains("\"scope\":\"forgeai\"")
        ));
        verify(this.terminalTabLauncher).launch("mock-codex-command", "/dev/ttys999");
    }
}
