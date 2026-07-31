package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.port.KnowledgeActiveProfileClient;
import com.sitionix.forgeai.domain.usecase.GetActiveProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetActiveProfileUseCase implements GetActiveProfile {

    private final KnowledgeActiveProfileClient knowledgeActiveProfileClient;

    @Override
    public ActiveProfile execute() {
        return this.knowledgeActiveProfileClient.getActiveProfile();
    }
}
