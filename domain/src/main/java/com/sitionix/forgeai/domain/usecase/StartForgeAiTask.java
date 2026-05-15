package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.Ticket;

/**
 * Starts a Forge AI task execution.
 */
public interface StartForgeAiTask {

    Ticket execute(ForgeAiStartCommand command);
}
