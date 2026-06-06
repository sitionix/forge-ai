package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.operator.TicketOperatorTerminalLauncher;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@IntegrationTest(properties = "forge.ai.operator.ticket-terminal.auto-open-on-ticket-start=true")
class TicketOperatorTerminalAutoOpenIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TicketOperatorTerminalLauncher ticketOperatorTerminalLauncher;

    @Test
    @DisplayName("Should auto-open one ticket terminal from start flow")
    void givenStartForgeRequest_whenTicketCreated_thenOpenExactlyOneTicketTerminal() {
        when(this.ticketOperatorTerminalLauncher.openTicketTerminal(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.startForge())
                .assertDefault();

        final ArgumentCaptor<UUID> ticketIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(this.ticketOperatorTerminalLauncher, times(1))
                .openTicketTerminal(ticketIdCaptor.capture(), anyString(), anyString(), anyString(), anyString());

        assertThat(ticketIdCaptor.getValue()).isNotNull();
    }
}
