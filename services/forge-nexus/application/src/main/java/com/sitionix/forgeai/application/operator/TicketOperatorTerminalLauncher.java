package com.sitionix.forgeai.application.operator;

import java.util.UUID;

public interface TicketOperatorTerminalLauncher {

    boolean openTicketTerminal(UUID ticketId,
                               String ticketKey,
                               String baseUrl,
                               String watcherId,
                               String verbosity);
}
