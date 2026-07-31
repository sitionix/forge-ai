package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.domain.port.KnowledgeActiveProfileClient;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileRequest;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KnowledgeActiveProfileClientAdapter implements KnowledgeActiveProfileClient {

    private final KnowledgeActiveProfileHttpClient httpClient;
    private final KnowledgeActiveProfileClientMapper mapper;
    private final KnowledgeClientCallExecutor clientCallExecutor;

    @Override
    public ActiveProfile getActiveProfile() {
        final KnowledgeActiveProfileResponse response =
                this.clientCallExecutor.execute(this.httpClient::getActiveProfile);

        return this.mapper.toDomain(response);
    }

    @Override
    public ActiveLlmProfileUpdateResult updateActiveLlmProfile(final UpdateActiveLlmProfileCommand command) {
        final KnowledgeActiveLlmProfileRequest request =
                this.mapper.toRequest(command);

        final KnowledgeActiveLlmProfileResponse response =
                this.clientCallExecutor.execute(
                        () -> this.httpClient.updateActiveLlmProfile(request)
                );

        return this.mapper.toDomain(response);
    }
}
