package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

final class KnowledgeClientHttpStatusException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    KnowledgeClientHttpStatusException(final int statusCode, final String responseBody) {
        super("Knowledge HTTP response status: " + statusCode);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    int statusCode() {
        return this.statusCode;
    }

    String responseBody() {
        return this.responseBody;
    }
}
