package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.application.laneexecution.LaneExecutionProgressService;
import com.sitionix.forgeai.application.operator.TicketOperatorEventService;
import com.sitionix.forgeai.application.operator.TicketOperatorRunService;
import com.sitionix.forgeai.application.operator.TicketOperatorTerminalProperties;
import com.sitionix.forgeai.application.testsupport.InMemoryTicketOperatorEventRepository;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRun;
import com.sitionix.forgeai.domain.model.operator.TicketOperatorRunStatus;
import com.sitionix.forgeai.domain.repository.CodexSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManageTicketOperatorRunsUseCaseTest {

    @Mock
    private TicketOperatorRunService ticketOperatorRunService;
    @Mock
    private LaneExecutionProgressService laneExecutionProgressService;
    @Mock
    private CodexSessionRepository codexSessionRepository;

    private ManageTicketOperatorRunsUseCase useCase;

    @BeforeEach
    void setUp() {
        this.useCase = new ManageTicketOperatorRunsUseCase(
                this.ticketOperatorRunService,
                this.laneExecutionProgressService,
                this.codexSessionRepository,
                new TicketOperatorTerminalProperties(),
                new TicketOperatorEventService(new InMemoryTicketOperatorEventRepository())
        );
    }

    @Test
    void givenTicketInterrupt_whenExecutionsBelongToTicket_thenInterruptOnlyThatTicket() {
        final UUID ticketA = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
        final LaneExecution withTurn = this.execution(
                ticketA,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "session-a1",
                "turn-a1"
        );
        final LaneExecution withoutTurn = this.execution(
                ticketA,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "session-a2",
                null
        );
        when(this.ticketOperatorRunService.markCancelRequested(ticketA, "terminal-closed")).thenReturn(this.run(ticketA, TicketOperatorRunStatus.CANCEL_REQUESTED));
        when(this.ticketOperatorRunService.markInterrupting(ticketA, "terminal-closed")).thenReturn(this.run(ticketA, TicketOperatorRunStatus.INTERRUPTING));
        when(this.ticketOperatorRunService.markCancelled(ticketA, "terminal-closed")).thenReturn(this.run(ticketA, TicketOperatorRunStatus.CANCELLED));
        when(this.laneExecutionProgressService.findActiveExecutionsByTicket(ticketA)).thenReturn(List.of(withTurn, withoutTurn));

        this.useCase.interruptTicket(ticketA, "terminal-closed");

        verify(this.laneExecutionProgressService).markCancelRequested(withTurn.getId());
        verify(this.laneExecutionProgressService).markCancelRequested(withoutTurn.getId());
        verify(this.codexSessionRepository).interruptTurn("session-a1", "turn-a1", Duration.ofSeconds(10));
        verify(this.codexSessionRepository, never()).interruptTurn(eq("session-a2"), any(), any());
        verify(this.codexSessionRepository).closeSession("session-a1");
        verify(this.codexSessionRepository).closeSession("session-a2");
        verify(this.laneExecutionProgressService).markInterrupted(withTurn.getId(), "Ticket operator interrupt: terminal-closed");
        verify(this.laneExecutionProgressService).markInterrupted(withoutTurn.getId(), "Ticket operator interrupt: terminal-closed");
    }

    private TicketOperatorRun run(final UUID ticketId, final TicketOperatorRunStatus status) {
        return TicketOperatorRun.builder()
                .ticketId(ticketId)
                .ticketKey("SITIONIX-1")
                .status(status)
                .build();
    }

    private LaneExecution execution(final UUID ticketId, final UUID executionId, final String sessionId, final String turnId) {
        return LaneExecution.builder()
                .id(executionId)
                .ticketId(ticketId)
                .laneId(UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222"))
                .agentId("analyzer")
                .scope("forge-ai")
                .sessionId(sessionId)
                .activeTurnId(turnId)
                .processPid(42L)
                .startedAt(LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
                .build();
    }
}
