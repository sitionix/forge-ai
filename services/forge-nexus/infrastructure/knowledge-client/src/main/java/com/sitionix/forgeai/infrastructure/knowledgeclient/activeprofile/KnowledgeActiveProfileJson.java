package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

final class KnowledgeActiveProfileJson {

    private final ObjectMapper objectMapper;

    KnowledgeActiveProfileJson(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    ObjectMapper objectMapper() {
        return this.objectMapper;
    }
}
