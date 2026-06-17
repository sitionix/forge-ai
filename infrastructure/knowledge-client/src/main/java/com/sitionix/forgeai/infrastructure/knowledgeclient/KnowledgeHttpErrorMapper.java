package com.sitionix.forgeai.infrastructure.knowledgeclient;

import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayErrorCode;

public class KnowledgeHttpErrorMapper {

    public KnowledgeGatewayErrorCode map(final int status) {
        if (status == 504) {
            return KnowledgeGatewayErrorCode.KNOWLEDGE_TIMEOUT;
        }
        if (status == 503) {
            return KnowledgeGatewayErrorCode.KNOWLEDGE_UNAVAILABLE;
        }
        if (status == 404) {
            return KnowledgeGatewayErrorCode.KNOWLEDGE_NOT_FOUND;
        }
        if (status == 409) {
            return KnowledgeGatewayErrorCode.KNOWLEDGE_CONFLICT;
        }
        if (status >= 400 && status < 500) {
            return KnowledgeGatewayErrorCode.KNOWLEDGE_REQUEST_FAILED;
        }
        return KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE;
    }
}
