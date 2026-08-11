package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.domain.model.CodexRuntimeEffort;
import com.sitionix.forgeagent.domain.model.CodexRuntimeModel;
import com.sitionix.forgeagent.domain.model.CodexRuntimeProvider;
import com.sitionix.forgeagent.domain.model.RuntimeProviderStatus;
import com.sitionix.forgeagent.domain.port.CodexRuntimePort;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodexRuntimeAdapter implements CodexRuntimePort {

    private static final String PROVIDER_ID = "codex";
    private static final String DISPLAY_NAME = "Codex";

    private final ObjectMapper objectMapper;
    private final CodexRpcClient client;
    private final CodexAppServerProperties properties;

    @Override
    public CodexRuntimeProvider getModels() {
        final String version;
        try {
            version = this.client.version();
        } catch (final RuntimeException e) {
            log.warn("Codex runtime unavailable during initialization errorType={}", e.getClass().getSimpleName());
            return new CodexRuntimeProvider(PROVIDER_ID, DISPLAY_NAME, RuntimeProviderStatus.UNAVAILABLE, null, List.of());
        }
        try {
            return new CodexRuntimeProvider(PROVIDER_ID, DISPLAY_NAME, RuntimeProviderStatus.READY, version, this.readAllModels());
        } catch (final RuntimeException e) {
            log.warn("Codex runtime degraded during model discovery errorType={}", e.getClass().getSimpleName());
            return new CodexRuntimeProvider(PROVIDER_ID, DISPLAY_NAME, RuntimeProviderStatus.DEGRADED, version, List.of());
        }
    }

    private List<CodexRuntimeModel> readAllModels() {
        final List<CodexRuntimeModel> models = new ArrayList<>();
        final Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            if (pages >= this.properties.getModelListMaxPages()) {
                throw new CodexTransportException("Codex model/list pagination exceeded maximum page count");
            }
            pages++;
            final ObjectNode params = this.objectMapper.createObjectNode();
            params.put("includeHidden", false);
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            final JsonNode result = this.client.request("model/list", params);
            if (!result.isObject()) {
                throw new CodexTransportException("Codex model/list result was not an object");
            }
            final JsonNode data = result.path("data");
            if (!data.isArray()) {
                throw new CodexTransportException("Codex model/list data was not a list");
            }
            for (final JsonNode rawModel : data) {
                final CodexRuntimeModel model = this.mapModel(rawModel);
                if (model != null) {
                    models.add(model);
                }
            }
            final String nextCursor = this.textOrNull(result.path("nextCursor"));
            if (nextCursor == null) {
                return models;
            }
            if (!seenCursors.add(nextCursor)) {
                throw new CodexTransportException("Codex model/list pagination cursor repeated");
            }
            cursor = nextCursor;
        }
    }

    private CodexRuntimeModel mapModel(final JsonNode rawModel) {
        if (!rawModel.isObject()) {
            throw new CodexTransportException("Codex model entry was not an object");
        }
        if (rawModel.path("hidden").asBoolean(false)) {
            return null;
        }
        final String modelId = this.requiredText(rawModel, "id", "Codex model entry is missing id");
        final String displayName = this.requiredText(rawModel, "displayName", "Codex model entry is missing displayName");
        final List<CodexRuntimeEffort> efforts = new ArrayList<>();
        final JsonNode rawEfforts = rawModel.path("supportedReasoningEfforts");
        if (rawEfforts.isArray()) {
            for (final JsonNode rawEffort : rawEfforts) {
                if (!rawEffort.isObject()) {
                    continue;
                }
                final String effortId = this.textOrNull(rawEffort.path("reasoningEffort"));
                final String description = this.textOrNull(rawEffort.path("description"));
                if (effortId != null && description != null) {
                    efforts.add(new CodexRuntimeEffort(effortId, description));
                }
            }
        }
        return new CodexRuntimeModel(
                modelId,
                displayName,
                this.textOrNull(rawModel.path("description")),
                efforts
        );
    }

    private String requiredText(final JsonNode node, final String field, final String message) {
        final String value = this.textOrNull(node.path(field));
        if (value == null) {
            throw new CodexTransportException(message);
        }
        return value;
    }

    private String textOrNull(final JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        final String text = node.asText().trim();
        return text.isBlank() ? null : text;
    }
}
