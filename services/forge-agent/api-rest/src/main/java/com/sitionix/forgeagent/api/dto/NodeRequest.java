package com.sitionix.forgeagent.api.dto;

import java.util.ArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.List;
import java.util.UUID;

public record NodeRequest(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortRequest> inputs,
        List<NodePortRequest> outputs,
        NodePositionRequest position,
        String scopeMode,
        String contextMode,
        String contextGroupKey,
        String nodeType,
        boolean includeTaskRepositories,
        List<UUID> workspaceRepositoryIds) {
    public NodeRequest(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortRequest> inputs,
        List<NodePortRequest> outputs,
        NodePositionRequest position,
        String scopeMode,
        String contextMode,
        String contextGroupKey,
        String nodeType) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, contextGroupKey, nodeType, true, List.of());
    }

    public NodeRequest {
        nodeType = nodeType == null ? "AGENT" : nodeType;
    }

    public NodeRequest(UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortRequest> inputs,
        List<NodePortRequest> outputs,
        NodePositionRequest position,
        String scopeMode,
        String contextMode,
        String contextGroupKey) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, contextGroupKey, "AGENT");
    }

    public NodeRequest(final UUID id, final UUID targetId, final String inputMode,
                       final List<NodePortRequest> inputs, final List<NodePortRequest> outputs,
                       final NodePositionRequest position, final String scopeMode) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, null);
    }

    public NodeRequest(UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortRequest> inputs,
        List<NodePortRequest> outputs,
        NodePositionRequest position,
        String scopeMode,
        String contextMode) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, null);
    }

    @JsonCreator
    public static NodeRequest fromJson(
            @JsonProperty("id") UUID id,
            @JsonProperty("targetId") UUID targetId,
            @JsonProperty("inputMode") String inputMode,
            @JsonProperty("inputs") List<NodePortRequest> inputs,
            @JsonProperty("outputs") List<NodePortRequest> outputs,
            @JsonProperty("position") NodePositionRequest position,
            @JsonProperty("scopeMode") String scopeMode,
            @JsonProperty("contextMode") String contextMode,
            @JsonProperty("contextGroupKey") String contextGroupKey,
            @JsonProperty("nodeType") String nodeType,
            @JsonProperty("includeTaskRepositories") JsonNode include,
            @JsonProperty("workspaceRepositoryIds") JsonNode repositories) {
        if (include != null && !include.isBoolean()) {
            throw new IllegalArgumentException("includeTaskRepositories must be a boolean");
        }
        final var ids = new ArrayList<UUID>();
        if (repositories != null) {
            if (!repositories.isArray()) throw new IllegalArgumentException("workspaceRepositoryIds must be an array");
            for (var idValue : repositories) {
                if (!idValue.isTextual()) throw new IllegalArgumentException("workspaceRepositoryIds must contain UUID strings");
                ids.add(UUID.fromString(idValue.textValue()));
            }
        }
        return new NodeRequest(id, targetId, inputMode, inputs, outputs, position, scopeMode,
                contextMode, contextGroupKey, nodeType, include == null || include.booleanValue(), List.copyOf(ids));
    }
}
