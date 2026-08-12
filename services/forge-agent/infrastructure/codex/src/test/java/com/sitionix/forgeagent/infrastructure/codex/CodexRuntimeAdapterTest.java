package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.RuntimeProviderStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodexRuntimeAdapterTest {

    private static final String MODEL_LIST = "model/list";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsVisibleModelsAndReasoningEffortsFromModelList() throws Exception {
        final FakeClient client = new FakeClient("codex 1.2.3");
        client.add("""
                {
                  "data": [
                    {
                      "id": "discovered-model",
                      "displayName": "Discovered Model",
                      "description": "Live model",
                      "supportedReasoningEfforts": [
                        {"reasoningEffort": "medium", "description": "Medium reasoning"}
                      ]
                    },
                    {
                      "id": "hidden-model",
                      "displayName": "Hidden",
                      "hidden": true
                    }
                  ]
                }
                """);

        final var provider = this.adapter(client).getModels();

        assertThat(provider.status()).isEqualTo(RuntimeProviderStatus.READY);
        assertThat(provider.version()).isEqualTo("codex 1.2.3");
        assertThat(provider.models()).singleElement().satisfies(model -> {
            assertThat(model.modelId()).isEqualTo("discovered-model");
            assertThat(model.displayName()).isEqualTo("Discovered Model");
            assertThat(model.description()).isEqualTo("Live model");
            assertThat(model.efforts()).singleElement().satisfies(effort -> {
                assertThat(effort.effortId()).isEqualTo("medium");
                assertThat(effort.description()).isEqualTo("Medium reasoning");
            });
        });
        assertThat(client.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(MODEL_LIST);
            assertThat(request.params().path("includeHidden").asBoolean()).isFalse();
        });
    }

    @Test
    void followsPaginationUntilNextCursorIsAbsent() {
        final FakeClient client = new FakeClient("codex 1.2.3");
        client.add("{\"data\":[{\"id\":\"model-a\",\"displayName\":\"Model A\"}],\"nextCursor\":\"cursor-b\"}");
        client.add("{\"data\":[{\"id\":\"model-b\",\"displayName\":\"Model B\"}]}");

        final var provider = this.adapter(client).getModels();

        assertThat(provider.status()).isEqualTo(RuntimeProviderStatus.READY);
        assertThat(provider.models()).extracting("modelId").containsExactly("model-a", "model-b");
        assertThat(client.requests()).hasSize(2);
        assertThat(client.requests().get(1).params().path("cursor").asText()).isEqualTo("cursor-b");
    }

    @Test
    void repeatedCursorDegradesProvider() {
        final FakeClient client = new FakeClient("codex 1.2.3");
        client.add("{\"data\":[],\"nextCursor\":\"same\"}");
        client.add("{\"data\":[],\"nextCursor\":\"same\"}");

        final var provider = this.adapter(client).getModels();

        assertThat(provider.status()).isEqualTo(RuntimeProviderStatus.DEGRADED);
        assertThat(provider.models()).isEmpty();
    }

    @Test
    void maximumPageCountDegradesProvider() {
        final FakeClient client = new FakeClient("codex 1.2.3");
        client.add("{\"data\":[],\"nextCursor\":\"a\"}");
        client.add("{\"data\":[],\"nextCursor\":\"b\"}");
        final CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setModelListMaxPages(1);

        final var provider = new CodexRuntimeAdapter(this.objectMapper, client, properties).getModels();

        assertThat(provider.status()).isEqualTo(RuntimeProviderStatus.DEGRADED);
        assertThat(provider.models()).isEmpty();
    }

    @Test
    void nonObjectModelListResponseDegradesProvider() {
        final FakeClient client = new FakeClient("codex 1.2.3");
        client.add("[]");

        final var provider = this.adapter(client).getModels();

        assertThat(provider.status()).isEqualTo(RuntimeProviderStatus.DEGRADED);
        assertThat(provider.models()).isEmpty();
    }

    @Test
    void missingDataListDegradesProvider() {
        final FakeClient client = new FakeClient("codex 1.2.3");
        client.add("{\"nextCursor\":\"later\"}");

        final var provider = this.adapter(client).getModels();

        assertThat(provider.status()).isEqualTo(RuntimeProviderStatus.DEGRADED);
        assertThat(provider.models()).isEmpty();
    }

    @Test
    void malformedModelEntryDegradesProvider() {
        final FakeClient client = new FakeClient("codex 1.2.3");
        client.add("{\"data\":[{\"displayName\":\"Missing id\"}]}");

        final var provider = this.adapter(client).getModels();

        assertThat(provider.status()).isEqualTo(RuntimeProviderStatus.DEGRADED);
        assertThat(provider.models()).isEmpty();
    }

    @Test
    void startupFailureReturnsUnavailable() {
        final FakeClient client = new FakeClient("codex 1.2.3");
        client.failVersion = true;

        final var provider = this.adapter(client).getModels();

        assertThat(provider.status()).isEqualTo(RuntimeProviderStatus.UNAVAILABLE);
        assertThat(provider.models()).isEmpty();
    }

    @Test
    void invalidInitializationReturnsUnavailable() {
        final FakeClient client = new FakeClient("codex 1.2.3");
        client.failVersion = true;

        final var provider = this.adapter(client).getModels();

        assertThat(provider.status()).isEqualTo(RuntimeProviderStatus.UNAVAILABLE);
        assertThat(provider.version()).isNull();
        assertThat(provider.models()).isEmpty();
    }

    @Test
    void modelListFailureAfterVersionReturnsDegraded() {
        final FakeClient client = new FakeClient("codex 1.2.3");
        client.failRequest = true;

        final var provider = this.adapter(client).getModels();

        assertThat(provider.status()).isEqualTo(RuntimeProviderStatus.DEGRADED);
        assertThat(provider.version()).isEqualTo("codex 1.2.3");
        assertThat(provider.models()).isEmpty();
    }

    private CodexRuntimeAdapter adapter(final FakeClient client) {
        return new CodexRuntimeAdapter(this.objectMapper, client, new CodexAppServerProperties());
    }

    private final class FakeClient implements CodexRpcClient {
        private final String version;
        private final List<JsonNode> responses = new ArrayList<>();
        private final List<Request> requests = new ArrayList<>();
        private boolean failVersion;
        private boolean failRequest;

        private FakeClient(final String version) {
            this.version = version;
        }

        private void add(final String responseJson) {
            try {
                this.responses.add(objectMapper.readTree(responseJson));
            } catch (final Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        private List<Request> requests() {
            return this.requests;
        }

        @Override
        public String version() {
            if (this.failVersion) {
                throw new CodexTransportException("startup failed");
            }
            return this.version;
        }

        @Override
        public JsonNode request(final String method, final JsonNode params) {
            if (this.failRequest) {
                throw new CodexTransportException("request failed");
            }
            this.requests.add(new Request(method, params.deepCopy()));
            return this.responses.remove(0);
        }

        @Override
        public void close() {
        }
    }

    private record Request(String method, JsonNode params) {
    }
}
