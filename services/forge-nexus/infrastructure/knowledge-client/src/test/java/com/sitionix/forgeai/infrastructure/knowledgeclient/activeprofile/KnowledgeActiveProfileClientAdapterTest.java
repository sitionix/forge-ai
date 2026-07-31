package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.exception.KnowledgeClientException;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfile;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileRequest;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileDetails;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeActiveProfileClientAdapterTest {

    @Mock
    private KnowledgeActiveProfileHttpClient httpClient;

    @Mock
    private KnowledgeActiveProfileClientMapper mapper;

    private KnowledgeActiveProfileClientProperties properties;
    private KnowledgeActiveProfileClientAdapter adapter;

    @BeforeEach
    void setUp() {
        this.properties = enabledProperties();
        this.adapter = new KnowledgeActiveProfileClientAdapter(
                this.httpClient,
                this.mapper,
                this.properties,
                new KnowledgeClientCallExecutor(new KnowledgeActiveProfileJson(new ObjectMapper()))
        );
    }

    @Test
    void getDelegatesToTypedHttpClient() {
        // given
        final KnowledgeActiveProfileResponse clientResponse = new KnowledgeActiveProfileResponse(1L, details(), null);
        final ActiveProfile domain = new ActiveProfile(1, new ActiveLlmProfile("ollama", "qwen", null), null);
        when(this.httpClient.getActiveProfile()).thenReturn(clientResponse);
        when(this.mapper.toDomain(clientResponse)).thenReturn(domain);

        // when
        final ActiveProfile result = this.adapter.getActiveProfile();

        // then
        assertThat(result).isSameAs(domain);
        verify(this.httpClient).getActiveProfile();
    }

    @Test
    void updateMapsCommandToTypedRequest() {
        // given
        final UpdateActiveLlmProfileCommand command = new UpdateActiveLlmProfileCommand(3, "ollama", "qwen", null);
        final KnowledgeActiveLlmProfileRequest request = new KnowledgeActiveLlmProfileRequest(3, "ollama", "qwen", null);
        final KnowledgeActiveLlmProfileResponse clientResponse = new KnowledgeActiveLlmProfileResponse(4L, details());
        final ActiveLlmProfileUpdateResult domain = new ActiveLlmProfileUpdateResult(4, new ActiveLlmProfile("ollama", "qwen", null));
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.updateActiveLlmProfile(request)).thenReturn(clientResponse);
        when(this.mapper.toDomain(clientResponse)).thenReturn(domain);

        // when
        this.adapter.updateActiveLlmProfile(command);

        // then
        verify(this.mapper).toRequest(command);
        verify(this.httpClient).updateActiveLlmProfile(request);
    }

    @Test
    void updateMapsTypedResponseToDomainResult() {
        // given
        final UpdateActiveLlmProfileCommand command = new UpdateActiveLlmProfileCommand(3, "ollama", "qwen", null);
        final KnowledgeActiveLlmProfileRequest request = new KnowledgeActiveLlmProfileRequest(3, "ollama", "qwen", null);
        final KnowledgeActiveLlmProfileResponse clientResponse = new KnowledgeActiveLlmProfileResponse(4L, details());
        final ActiveLlmProfileUpdateResult domain = new ActiveLlmProfileUpdateResult(4, new ActiveLlmProfile("ollama", "qwen", null));
        when(this.mapper.toRequest(command)).thenReturn(request);
        when(this.httpClient.updateActiveLlmProfile(request)).thenReturn(clientResponse);
        when(this.mapper.toDomain(clientResponse)).thenReturn(domain);

        // when
        final ActiveLlmProfileUpdateResult result = this.adapter.updateActiveLlmProfile(command);

        // then
        assertThat(result).isSameAs(domain);
        verify(this.mapper).toDomain(clientResponse);
    }

    @Test
    void controlledClientExceptionIsPreserved() {
        // given
        final KnowledgeClientException exception = new KnowledgeClientException(
                409,
                "ACTIVE_PROFILE_REVISION_CONFLICT",
                "The active profile was changed by another request",
                "corr-409",
                null
        );
        when(this.httpClient.getActiveProfile()).thenThrow(exception);

        // when // then
        assertThatThrownBy(() -> this.adapter.getActiveProfile()).isSameAs(exception);
    }

    @Test
    void disabledClientMapsToUnavailable() {
        // given
        this.properties.setEnabled(false);

        // when // then
        assertThatThrownBy(() -> this.adapter.getActiveProfile())
                .isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_UNAVAILABLE");
                });
        verifyNoMoreInteractions(this.httpClient);
    }

    @Test
    void resourceAccessFailureMapsToUnavailable() {
        // given
        when(this.httpClient.getActiveProfile()).thenThrow(new ResourceAccessException("request failed"));

        // when // then
        assertThatThrownBy(() -> this.adapter.getActiveProfile())
                .isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_UNAVAILABLE");
                });
    }

    @Test
    void typedTimeoutCauseMapsToUnavailable() {
        // given
        when(this.httpClient.getActiveProfile()).thenThrow(new RestClientException("client failure", new SocketTimeoutException()));

        // when // then
        assertThatThrownBy(() -> this.adapter.getActiveProfile())
                .isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_UNAVAILABLE");
                });
    }

    @Test
    void malformedSuccessMapsToInvalidResponse() {
        // given
        when(this.httpClient.getActiveProfile()).thenThrow(new HttpMessageConversionException("bad body"));

        // when // then
        assertThatThrownBy(() -> this.adapter.getActiveProfile())
                .isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(502);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
                });
    }

    @Test
    void nestedMalformedSuccessMapsToInvalidResponse() {
        // given
        when(this.httpClient.getActiveProfile()).thenThrow(new RestClientException(
                "client failure",
                new HttpMessageConversionException("bad body")
        ));

        // when // then
        assertThatThrownBy(() -> this.adapter.getActiveProfile())
                .isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(502);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
                });
    }

    @Test
    void invalidDomainMappingMapsToInvalidResponse() {
        // given
        final KnowledgeActiveProfileResponse clientResponse = new KnowledgeActiveProfileResponse(1L, details(), null);
        when(this.httpClient.getActiveProfile()).thenReturn(clientResponse);
        when(this.mapper.toDomain(clientResponse)).thenThrow(new IllegalArgumentException("llmProfile must not be null"));

        // when // then
        assertThatThrownBy(() -> this.adapter.getActiveProfile())
                .isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(502);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
                });
    }

    @Test
    void unexpectedClientFailureMapsToSafeBadGateway() {
        // given
        when(this.httpClient.getActiveProfile()).thenThrow(new RestClientException("unexpected"));

        // when // then
        assertThatThrownBy(() -> this.adapter.getActiveProfile())
                .isInstanceOfSatisfying(KnowledgeClientException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(502);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_ERROR");
                });
    }

    private static KnowledgeActiveProfileClientProperties enabledProperties() {
        final KnowledgeActiveProfileClientProperties properties = new KnowledgeActiveProfileClientProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static KnowledgeActiveLlmProfileDetails details() {
        return new KnowledgeActiveLlmProfileDetails("ollama", "qwen", null);
    }
}
