package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import java.util.UUID;

/**
 * Starts a Forge AI task execution.
 */
public interface StartForgeAiTask {

    Ticket execute(ForgeAiStartCommand command);

    Ticket createOpen(ForgeAiStartCommand command);

    Ticket executeOpen(UUID ticketId);
}
