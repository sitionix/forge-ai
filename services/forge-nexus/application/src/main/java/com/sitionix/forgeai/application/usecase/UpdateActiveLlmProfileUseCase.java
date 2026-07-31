package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.domain.port.KnowledgeActiveProfileClient;
import com.sitionix.forgeai.domain.usecase.UpdateActiveLlmProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UpdateActiveLlmProfileUseCase implements UpdateActiveLlmProfile {

    private final KnowledgeActiveProfileClient knowledgeActiveProfileClient;

    @Override
    public ActiveLlmProfileUpdateResult execute(final UpdateActiveLlmProfileCommand command) {
        return this.knowledgeActiveProfileClient.updateActiveLlmProfile(command);
    }
}
