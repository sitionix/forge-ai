package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.exception.KnowledgeClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeActiveProfileStatusHandlerTest {

    private KnowledgeClientCallExecutor executor;

    @BeforeEach
    void setUp() {
        this.executor = new KnowledgeClientCallExecutor(new KnowledgeActiveProfileJson(new ObjectMapper()));
    }

    @Test
    void revisionConflictPreservesControlledError() {
        // given
        final KnowledgeClientHttpStatusException upstream = new KnowledgeClientHttpStatusException(409, """
                {"code":"ACTIVE_PROFILE_REVISION_CONFLICT","message":"The active profile was changed by another request","correlationId":"corr-409"}
                """);

        // when // then
        assertThatThrownBy(() -> this.executor.execute(() -> {
            throw upstream;
        })).isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
            assertThat(exception.statusCode()).isEqualTo(409);
            assertThat(exception.code()).isEqualTo("ACTIVE_PROFILE_REVISION_CONFLICT");
            assertThat(exception.correlationId()).isEqualTo("corr-409");
        });
    }

    @Test
    void malformedControlledErrorMapsToInvalidResponse() {
        // given
        final KnowledgeClientHttpStatusException upstream = new KnowledgeClientHttpStatusException(409, "{\"code\":\"ACTIVE_PROFILE_REVISION_CONFLICT\"");

        // when // then
        assertThatThrownBy(() -> this.executor.execute(() -> {
            throw upstream;
        })).isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
            assertThat(exception.statusCode()).isEqualTo(502);
            assertThat(exception.code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
        });
    }

    @Test
    void unexpectedServerErrorMapsToUpstreamFailure() {
        // given
        final KnowledgeClientHttpStatusException upstream = new KnowledgeClientHttpStatusException(500, "internal");

        // when // then
        assertThatThrownBy(() -> this.executor.execute(() -> {
            throw upstream;
        })).isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
            assertThat(exception.statusCode()).isEqualTo(502);
            assertThat(exception.code()).isEqualTo("UPSTREAM_ERROR");
        });
    }

    @Test
    void redirectMapsToInvalidResponse() {
        // given
        final KnowledgeClientHttpStatusException upstream = new KnowledgeClientHttpStatusException(302, "");

        // when // then
        assertThatThrownBy(() -> this.executor.execute(() -> {
            throw upstream;
        })).isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
            assertThat(exception.statusCode()).isEqualTo(502);
            assertThat(exception.code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
        });
    }
}
