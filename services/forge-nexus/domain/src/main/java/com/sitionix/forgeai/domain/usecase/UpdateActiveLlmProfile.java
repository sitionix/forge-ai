package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;

public interface UpdateActiveLlmProfile {

    ActiveLlmProfileUpdateResult execute(UpdateActiveLlmProfileCommand command);
}
