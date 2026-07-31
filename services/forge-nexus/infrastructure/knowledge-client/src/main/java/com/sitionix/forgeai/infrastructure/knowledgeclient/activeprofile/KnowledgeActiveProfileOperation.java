package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import org.springframework.http.HttpMethod;

enum KnowledgeActiveProfileOperation {
    GET_ACTIVE_PROFILE("getActiveProfile"),
    UPDATE_ACTIVE_LLM_PROFILE("updateActiveLlmProfile"),
    ACTIVE_PROFILE_REQUEST("activeProfileRequest");

    private final String diagnosticName;

    KnowledgeActiveProfileOperation(final String diagnosticName) {
        this.diagnosticName = diagnosticName;
    }

    String diagnosticName() {
        return this.diagnosticName;
    }

    static KnowledgeActiveProfileOperation fromHttpMethod(final HttpMethod method) {
        if (HttpMethod.GET.equals(method)) {
            return GET_ACTIVE_PROFILE;
        }
        if (HttpMethod.PUT.equals(method)) {
            return UPDATE_ACTIVE_LLM_PROFILE;
        }
        return ACTIVE_PROFILE_REQUEST;
    }
}
