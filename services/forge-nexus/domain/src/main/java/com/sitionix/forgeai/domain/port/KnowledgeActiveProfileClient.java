package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;

public interface KnowledgeActiveProfileClient {

    ActiveProfile getActiveProfile();

    ActiveLlmProfileUpdateResult updateActiveLlmProfile(UpdateActiveLlmProfileCommand command);
}
