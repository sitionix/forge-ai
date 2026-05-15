package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ForgeAiStartTask;

/**
 * Runs Forge AI tasks through external execution transport.
 */
public interface ForgeAiTaskRunner {

    ForgeAiStartTask run(ForgeAiStartCommand command);
}
