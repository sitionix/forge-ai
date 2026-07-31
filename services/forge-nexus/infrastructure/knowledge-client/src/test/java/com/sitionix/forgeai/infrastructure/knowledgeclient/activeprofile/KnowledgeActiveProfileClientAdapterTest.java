package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.domain.model.activeprofile.LlmEffort;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import feign.Request;
import feign.RetryableException;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = KnowledgeActiveProfileClientAdapterTest.TestConfiguration.class,
        properties = {
                "forge.ai.infrastructure.knowledge.enabled=true",
                "forge.ai.infrastructure.knowledge.connect-timeout=200ms",
                "forge.ai.infrastructure.knowledge.read-timeout=500ms"
        }
)
class KnowledgeActiveProfileClientAdapterTest {

    private static final MockWebServer SERVER = startedServer();

    @Autowired
    private KnowledgeActiveProfileClientAdapter adapter;

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.shutdown();
    }

    @AfterEach
    void drainRequests() throws InterruptedException {
        while (SERVER.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            continue;
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("forge.ai.infrastructure.knowledge.base-url", () -> SERVER.url("/").toString());
    }

    @Test
    void getCallsTypedKnowledgeEndpointAndDeserializesUsageNull() throws Exception {
        SERVER.enqueue(json(200, """
                {"revision":1,"llmProfile":{"providerId":"ollama","modelId":"qwen2.5-coder:14b","effort":null},"usage":null}
                """));

        final var profile = this.adapter.getActiveProfile();

        assertThat(profile.revision()).isEqualTo(1);
        assertThat(profile.llmProfile().providerId()).isEqualTo("ollama");
        assertThat(profile.llmProfile().modelId()).isEqualTo("qwen2.5-coder:14b");
        assertThat(profile.llmProfile().effort()).isNull();
        assertThat(profile.usage()).isNull();

        final RecordedRequest request = SERVER.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/api/v1/knowledge/active-profile");
        assertThat(request.getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void putCallsTypedKnowledgeEndpointAndSerializesExactBody() throws Exception {
        SERVER.enqueue(json(200, """
                {"revision":4,"llmProfile":{"providerId":"codex","modelId":"gpt-5.6-sol","effort":{"effortId":"high"}}}
                """));

        final var result = this.adapter.updateActiveLlmProfile(new UpdateActiveLlmProfileCommand(
                3,
                "codex",
                "gpt-5.6-sol",
                new LlmEffort("high")
        ));

        assertThat(result.revision()).isEqualTo(4);
        assertThat(result.llmProfile().providerId()).isEqualTo("codex");
        assertThat(result.llmProfile().effort()).isEqualTo(new LlmEffort("high"));

        final RecordedRequest request = SERVER.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getMethod()).isEqualTo("PUT");
        assertThat(request.getPath()).isEqualTo("/api/v1/knowledge/active-profile/llm-profile");
        assertThat(request.getHeader("Content-Type")).contains("application/json");
        assertThat(request.getBody().readUtf8())
                .isEqualTo("{\"expectedRevision\":3,\"providerId\":\"codex\",\"modelId\":\"gpt-5.6-sol\",\"effort\":{\"effortId\":\"high\"}}");
    }

    @Test
    void getDeserializesUsageWindowsAndResetAt() {
        SERVER.enqueue(json(200, """
                {"revision":3,"llmProfile":{"providerId":"codex","modelId":"gpt-5.6-sol","effort":{"effortId":"high"}},"usage":{"windows":[{"kind":"PRIMARY","usedPercent":34,"windowDurationMinutes":300,"resetAt":"2026-07-31T12:00:00Z"},{"kind":"SECONDARY","usedPercent":61,"windowDurationMinutes":10080,"resetAt":"2026-08-04T09:00:00Z"}]}}
                """));

        final var profile = this.adapter.getActiveProfile();

        assertThat(profile.usage().windows()).hasSize(2);
        assertThat(profile.usage().windows().get(0).kind().name()).isEqualTo("PRIMARY");
        assertThat(profile.usage().windows().get(0).resetAt()).isEqualTo(Instant.parse("2026-07-31T12:00:00Z"));
        assertThat(profile.usage().windows().get(1).kind().name()).isEqualTo("SECONDARY");
    }

    @Test
    void controlledRevisionConflictIsPreserved() {
        SERVER.enqueue(json(409, """
                {"code":"ACTIVE_PROFILE_REVISION_CONFLICT","message":"The active profile was changed by another request","correlationId":"corr-409"}
                """));

        assertThatThrownBy(() -> this.adapter.updateActiveLlmProfile(new UpdateActiveLlmProfileCommand(
                3,
                "ollama",
                "qwen",
                null
        )))
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(409);
                    assertThat(exception.code()).isEqualTo("ACTIVE_PROFILE_REVISION_CONFLICT");
                    assertThat(exception.getMessage()).isEqualTo("The active profile was changed by another request");
                    assertThat(exception.correlationId()).isEqualTo("corr-409");
                });
    }

    @Test
    void controlledValidationAndUnavailableStatusesArePreserved() {
        SERVER.enqueue(json(400, """
                {"code":"ACTIVE_LLM_MODEL_NOT_FOUND","message":"The selected model was not found","correlationId":"corr-400"}
                """));
        SERVER.enqueue(json(404, """
                {"code":"ACTIVE_LLM_PROVIDER_NOT_FOUND","message":"The selected provider was not found","correlationId":"corr-404"}
                """));
        SERVER.enqueue(json(422, """
                {"code":"ACTIVE_LLM_EFFORT_NOT_SUPPORTED","message":"The selected effort is not supported","correlationId":"corr-422"}
                """));
        SERVER.enqueue(json(503, """
                {"code":"ACTIVE_LLM_PROVIDER_UNAVAILABLE","message":"The selected provider is unavailable","correlationId":"corr-503"}
                """));

        assertThatThrownBy(() -> this.adapter.updateActiveLlmProfile(new UpdateActiveLlmProfileCommand(3, "ollama", "missing", null)))
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(400);
                    assertThat(exception.code()).isEqualTo("ACTIVE_LLM_MODEL_NOT_FOUND");
                    assertThat(exception.correlationId()).isEqualTo("corr-400");
                });

        assertThatThrownBy(() -> this.adapter.getActiveProfile())
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(404);
                    assertThat(exception.code()).isEqualTo("ACTIVE_LLM_PROVIDER_NOT_FOUND");
                    assertThat(exception.correlationId()).isEqualTo("corr-404");
                });

        assertThatThrownBy(() -> this.adapter.updateActiveLlmProfile(new UpdateActiveLlmProfileCommand(3, "ollama", "qwen", new LlmEffort("unknown"))))
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(422);
                    assertThat(exception.code()).isEqualTo("ACTIVE_LLM_EFFORT_NOT_SUPPORTED");
                    assertThat(exception.correlationId()).isEqualTo("corr-422");
                });

        assertThatThrownBy(() -> this.adapter.getActiveProfile())
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("ACTIVE_LLM_PROVIDER_UNAVAILABLE");
                    assertThat(exception.correlationId()).isEqualTo("corr-503");
                });
    }

    @Test
    void connectionFailureMapsToUpstreamUnavailable() {
        final var failingClient = new KnowledgeActiveProfileFeignClient() {
            @Override
            public com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse getActiveProfile() {
                throw new RetryableException(
                        -1,
                        "Connection refused",
                        Request.HttpMethod.GET,
                        (Long) null,
                        Request.create(Request.HttpMethod.GET, "/api/v1/knowledge/active-profile", Collections.emptyMap(), null, null, null)
                );
            }

            @Override
            public com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse updateActiveLlmProfile(
                    final com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileRequest request) {
                throw new AssertionError("PUT should not be called");
            }
        };
        final var failingAdapter = new KnowledgeActiveProfileClientAdapter(
                failingClient,
                new KnowledgeActiveProfileClientMapperImpl(),
                new KnowledgeActiveProfileClientProperties()
        );

        assertThatThrownBy(failingAdapter::getActiveProfile)
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_UNAVAILABLE");
                });
    }

    @Test
    void malformedSuccessResponseMapsToBadGateway() {
        SERVER.enqueue(json(200, "{\"revision\":1,\"llmProfile\":{}"));

        assertThatThrownBy(() -> this.adapter.getActiveProfile())
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(502);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
                });
    }

    @Test
    void malformedErrorResponseMapsToBadGateway() {
        SERVER.enqueue(json(409, "{\"code\":\"ACTIVE_PROFILE_REVISION_CONFLICT\""));

        assertThatThrownBy(() -> this.adapter.getActiveProfile())
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(502);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_INVALID_RESPONSE");
                });
    }

    @Test
    void timeoutMapsToUpstreamUnavailable() {
        SERVER.enqueue(json(200, """
                {"revision":1,"llmProfile":{"providerId":"ollama","modelId":"qwen","effort":null},"usage":null}
                """).setBodyDelay(2, TimeUnit.SECONDS));

        assertThatThrownBy(() -> this.adapter.getActiveProfile())
                .isInstanceOfSatisfying(KnowledgeActiveProfileClientException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_UNAVAILABLE");
                });
    }

    private static MockResponse json(final int status, final String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body.strip());
    }

    private static MockWebServer startedServer() {
        final MockWebServer server = new MockWebServer();
        try {
            server.start();
            return server;
        } catch (final IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Configuration
    @EnableFeignClients(clients = KnowledgeActiveProfileFeignClient.class)
    @ImportAutoConfiguration(FeignAutoConfiguration.class)
    @Import({
            KnowledgeActiveProfileClientAdapter.class,
            KnowledgeActiveProfileFeignConfiguration.class,
            KnowledgeActiveProfileClientMapperImpl.class
    })
    static class TestConfiguration {
    }
}
