package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.ForgeAiStartTask;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;

/**
 * Starts a Forge AI task execution.
 */
public interface StartForgeAiTask {

    ForgeAiStartTask execute(ForgeAiStartCommand command);
}
