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
    private final KnowledgeActiveProfileClientProperties properties;
    private final KnowledgeClientCallExecutor clientCallExecutor;

    @Override
    public ActiveProfile getActiveProfile() {
        this.properties.requireEnabled();
        final KnowledgeActiveProfileResponse response = this.clientCallExecutor.execute(this.httpClient::getActiveProfile);
        return this.mapActiveProfileResponse(response);
    }

    @Override
    public ActiveLlmProfileUpdateResult updateActiveLlmProfile(final UpdateActiveLlmProfileCommand command) {
        this.properties.requireEnabled();
        final KnowledgeActiveLlmProfileRequest request = this.mapRequest(command);
        final KnowledgeActiveLlmProfileResponse response = this.clientCallExecutor.execute(
                () -> this.httpClient.updateActiveLlmProfile(request)
        );
        return this.mapUpdateResponse(response);
    }

    private KnowledgeActiveLlmProfileRequest mapRequest(final UpdateActiveLlmProfileCommand command) {
        try {
            return this.mapper.toRequest(command);
        } catch (final RuntimeException exception) {
            throw this.clientCallExecutor.dependencyFailure(exception);
        }
    }

    private ActiveProfile mapActiveProfileResponse(final KnowledgeActiveProfileResponse response) {
        try {
            final ActiveProfile activeProfile = this.mapper.toDomain(response);
            if (activeProfile == null) {
                throw new IllegalArgumentException("mapped active profile must not be null");
            }
            return activeProfile;
        } catch (final RuntimeException exception) {
            throw this.clientCallExecutor.badResponse(exception);
        }
    }

    private ActiveLlmProfileUpdateResult mapUpdateResponse(final KnowledgeActiveLlmProfileResponse response) {
        try {
            final ActiveLlmProfileUpdateResult result = this.mapper.toDomain(response);
            if (result == null) {
                throw new IllegalArgumentException("mapped active profile update result must not be null");
            }
            return result;
        } catch (final RuntimeException exception) {
            throw this.clientCallExecutor.badResponse(exception);
        }
    }
}
