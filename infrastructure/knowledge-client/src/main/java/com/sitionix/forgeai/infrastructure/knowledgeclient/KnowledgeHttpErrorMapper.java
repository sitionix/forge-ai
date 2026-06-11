package com.sitionix.forgeai.infrastructure.knowledgeclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayErrorCode;

public class KnowledgeHttpErrorMapper {

    public KnowledgeGatewayErrorCode map(final JsonNode node, final int status) {
        final String code = node.path("code").asText(null);
        if ("SEARCH_QUERY_INVALID".equals(code)) {
            return KnowledgeGatewayErrorCode.SEARCH_QUERY_INVALID;
        }
        if ("CONTEXT_QUERY_INVALID".equals(code)) {
            return KnowledgeGatewayErrorCode.CONTEXT_QUERY_INVALID;
        }
        if (status == 400 || status == 422) {
            return KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE;
        }
        if (status == 504) {
            return KnowledgeGatewayErrorCode.KNOWLEDGE_TIMEOUT;
        }
        if (status == 503) {
            return KnowledgeGatewayErrorCode.KNOWLEDGE_UNAVAILABLE;
        }
        return KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE;
    }
}
