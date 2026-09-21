package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.ArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeType;
import java.util.List;
import java.util.UUID;

public record NodeResponse(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortResponse> inputs,
        List<NodePortResponse> outputs,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey,
        AgentNodeType nodeType,
        boolean includeTaskRepositories,
        List<UUID> workspaceRepositoryIds) {
    public NodeResponse(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortResponse> inputs,
        List<NodePortResponse> outputs,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey,
        AgentNodeType nodeType) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, contextGroupKey, nodeType, true, java.util.List.of());
    }

    public NodeResponse {
        if (nodeType == null) {
            nodeType = AgentNodeType.AGENT;
        }
    }

    public NodeResponse(
        UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortResponse> inputs,
        List<NodePortResponse> outputs,
        NodePositionResponse position,
        String scopeMode,
        String contextMode,
        String contextGroupKey) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, contextGroupKey,
                AgentNodeType.AGENT);
    }

    public NodeResponse(UUID id,UUID targetId,String inputMode,List<NodePortResponse> inputs,List<NodePortResponse> outputs,NodePositionResponse position,String scopeMode) { this(id,targetId,inputMode,inputs,outputs,position,scopeMode,"FRESH_EACH_NODE_RUN"); }

    public NodeResponse(UUID id,
        UUID targetId,
        String inputMode,
        List<NodePortResponse> inputs,
        List<NodePortResponse> outputs,
        NodePositionResponse position,
        String scopeMode,
        String contextMode) {
        this(id, targetId, inputMode, inputs, outputs, position, scopeMode, contextMode, null);
    }
    @JsonCreator
    public static NodeResponse fromJson(
            @JsonProperty("id") UUID id,
            @JsonProperty("targetId") UUID targetId,
            @JsonProperty("inputMode") String inputMode,
            @JsonProperty("inputs") List<NodePortResponse> inputs,
            @JsonProperty("outputs") List<NodePortResponse> outputs,
            @JsonProperty("position") NodePositionResponse position,
            @JsonProperty("scopeMode") String scopeMode,
            @JsonProperty("contextMode") String contextMode,
            @JsonProperty("contextGroupKey") String contextGroupKey,
            @JsonProperty("nodeType") AgentNodeType nodeType,
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
        return new NodeResponse(id, targetId, inputMode, inputs, outputs, position, scopeMode,
                contextMode, contextGroupKey, nodeType, include == null || include.booleanValue(), List.copyOf(ids));
    }
}
