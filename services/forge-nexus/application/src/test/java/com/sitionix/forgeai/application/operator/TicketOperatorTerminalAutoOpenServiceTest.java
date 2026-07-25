package com.sitionix.forgeai.application.operator;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketOperatorTerminalAutoOpenServiceTest {

    @Mock
    private TicketOperatorRunService ticketOperatorRunService;

    @Mock
    private TicketOperatorTerminalLauncher ticketOperatorTerminalLauncher;

    private TicketOperatorTerminalProperties properties;
    private TicketOperatorTerminalAutoOpenService service;

    @BeforeEach
    void setUp() {
        this.properties = new TicketOperatorTerminalProperties();
        this.properties.setAutoOpenOnTicketStart(true);
        this.service = new TicketOperatorTerminalAutoOpenService(
                this.ticketOperatorRunService,
                this.properties,
                this.ticketOperatorTerminalLauncher
        );
    }

    @Test
    void givenAutoOpenDisabled_whenOpenIfConfigured_thenSkipLaunch() {
        this.properties.setAutoOpenOnTicketStart(false);

        this.service.openIfConfigured(this.ticket());

        verify(this.ticketOperatorTerminalLauncher, never()).openTicketTerminal(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void givenExistingWatcher_whenOpenIfConfigured_thenDoNotOpenDuplicateTerminal() {
        final Ticket ticket = this.ticket();
        when(this.ticketOperatorRunService.hasActiveWatcher(ticket.getId())).thenReturn(true);

        this.service.openIfConfigured(ticket);

        verify(this.ticketOperatorTerminalLauncher, never()).openTicketTerminal(any(), anyString(), anyString(), anyString(), anyString());
        verify(this.ticketOperatorRunService, never()).claimWatcherOpen(any(), anyString(), anyBoolean());
    }

    @Test
    void givenNoWatcher_whenOpenIfConfigured_thenClaimAndLaunchTicketTerminal() {
        final Ticket ticket = this.ticket();
        when(this.ticketOperatorRunService.hasActiveWatcher(ticket.getId())).thenReturn(false);
        when(this.ticketOperatorTerminalLauncher.openTicketTerminal(eq(ticket.getId()), eq(ticket.getTicketKey()), nullable(String.class), anyString(), eq("minimal")))
                .thenReturn(true);

        this.service.openIfConfigured(ticket);

        verify(this.ticketOperatorRunService).claimWatcherOpen(eq(ticket.getId()), anyString(), eq(true));
        verify(this.ticketOperatorTerminalLauncher).openTicketTerminal(eq(ticket.getId()), eq(ticket.getTicketKey()), nullable(String.class), anyString(), eq("minimal"));
    }

    @Test
    void givenLaunchFailure_whenOpenIfConfigured_thenReleaseWatcherClaim() {
        final Ticket ticket = this.ticket();
        when(this.ticketOperatorRunService.hasActiveWatcher(ticket.getId())).thenReturn(false);
        when(this.ticketOperatorTerminalLauncher.openTicketTerminal(eq(ticket.getId()), eq(ticket.getTicketKey()), nullable(String.class), anyString(), eq("minimal")))
                .thenReturn(false);

        this.service.openIfConfigured(ticket);

        verify(this.ticketOperatorRunService).releaseWatcherClaim(eq(ticket.getId()), anyString());
    }

    private Ticket ticket() {
        return Ticket.builder()
                .id(UUID.randomUUID())
                .ticketKey("SITIONIX-123")
                .lanes(List.of())
                .build();
    }
}
