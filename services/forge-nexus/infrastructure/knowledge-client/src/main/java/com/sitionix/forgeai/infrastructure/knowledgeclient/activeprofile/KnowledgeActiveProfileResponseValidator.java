package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmEffort;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileDetails;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeLlmUsage;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeLlmUsageWindow;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeLlmUsageWindowKind;
import java.util.EnumSet;
import java.util.List;

final class KnowledgeActiveProfileResponseValidator {

    void validateGetResponse(final KnowledgeActiveProfileResponse response) {
        this.required(response, "response");
        this.positive(response.revision(), "revision");
        this.validateLlmProfile(response.llmProfile());
        this.validateUsage(response.usage());
    }

    void validatePutResponse(final KnowledgeActiveLlmProfileResponse response) {
        this.required(response, "response");
        this.positive(response.revision(), "revision");
        this.validateLlmProfile(response.llmProfile());
    }

    private void validateLlmProfile(final KnowledgeActiveLlmProfileDetails llmProfile) {
        this.required(llmProfile, "llmProfile");
        this.text(llmProfile.providerId(), "providerId");
        this.text(llmProfile.modelId(), "modelId");
        this.validateEffort(llmProfile.effort());
    }

    private void validateEffort(final KnowledgeActiveLlmEffort effort) {
        if (effort != null) {
            this.text(effort.effortId(), "effortId");
        }
    }

    private void validateUsage(final KnowledgeLlmUsage usage) {
        if (usage == null) {
            return;
        }
        final List<KnowledgeLlmUsageWindow> windows = this.required(usage.windows(), "windows");
        if (windows.isEmpty() || windows.size() > 2) {
            throw new IllegalArgumentException("windows must contain one or two entries");
        }
        final EnumSet<KnowledgeLlmUsageWindowKind> seenKinds = EnumSet.noneOf(KnowledgeLlmUsageWindowKind.class);
        for (final KnowledgeLlmUsageWindow window : windows) {
            this.validateWindow(window, seenKinds);
        }
    }

    private void validateWindow(final KnowledgeLlmUsageWindow window,
                                final EnumSet<KnowledgeLlmUsageWindowKind> seenKinds) {
        this.required(window, "window");
        final KnowledgeLlmUsageWindowKind kind = this.required(window.kind(), "kind");
        if (!seenKinds.add(kind)) {
            throw new IllegalArgumentException("windows must not contain duplicate kinds");
        }
        final Integer usedPercent = this.required(window.usedPercent(), "usedPercent");
        if (usedPercent < 0 || usedPercent > 100) {
            throw new IllegalArgumentException("usedPercent must be between 0 and 100");
        }
        final Integer windowDurationMinutes = this.required(window.windowDurationMinutes(), "windowDurationMinutes");
        if (windowDurationMinutes <= 0) {
            throw new IllegalArgumentException("windowDurationMinutes must be positive");
        }
        this.required(window.resetAt(), "resetAt");
    }

    private Long positive(final Long value, final String fieldName) {
        final Long requiredValue = this.required(value, fieldName);
        if (requiredValue <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return requiredValue;
    }

    private String text(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private <T> T required(final T value, final String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }
}
