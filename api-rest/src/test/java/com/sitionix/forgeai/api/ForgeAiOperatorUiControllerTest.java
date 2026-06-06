package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiTicketGraphResponse;
import com.sitionix.forgeai.domain.usecase.GetOperatorUiReadModel.OperatorUiTicketListResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForgeAiOperatorUiControllerTest {

    private static final UUID TICKET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ForgeAiOperatorUiController controller;

    @Mock
    private GetOperatorUiReadModel getOperatorUiReadModel;

    @BeforeEach
    void setUp() {
        this.controller = new ForgeAiOperatorUiController(this.getOperatorUiReadModel);
    }

    @Test
    void givenLimit_whenTickets_thenDelegateToUseCase() {
        final OperatorUiTicketListResponse response = new OperatorUiTicketListResponse(List.of());
        when(this.getOperatorUiReadModel.tickets(25)).thenReturn(response);

        assertThat(this.controller.tickets(25)).isSameAs(response);
    }

    @Test
    void givenTicketId_whenGraph_thenDelegateToUseCase() {
        final OperatorUiTicketGraphResponse response = new OperatorUiTicketGraphResponse(
                TICKET_ID,
                "SITIONIX-142",
                "OPEN",
                null,
                "task",
                null,
                null,
                null,
                List.of()
        );
        when(this.getOperatorUiReadModel.graph(TICKET_ID)).thenReturn(response);

        assertThat(this.controller.graph(TICKET_ID)).isSameAs(response);
    }
}
