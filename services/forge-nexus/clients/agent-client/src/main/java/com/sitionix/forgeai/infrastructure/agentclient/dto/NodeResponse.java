package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeType;
import java.util.List;
import java.util.UUID;

@JsonDeserialize(builder = NodeResponse.Builder.class)
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
    @JsonPOJOBuilder(withPrefix = "")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class Builder {
        private UUID id;
        private UUID targetId;
        private String inputMode;
        private List<NodePortResponse> inputs;
        private List<NodePortResponse> outputs;
        private NodePositionResponse position;
        private String scopeMode;
        private String contextMode;
        private String contextGroupKey;
        private AgentNodeType nodeType;
        @JsonSetter(nulls = Nulls.FAIL)
        private Boolean includeTaskRepositories = true;
        @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
        private List<UUID> workspaceRepositoryIds = List.of();

        public NodeResponse build() {
            return new NodeResponse(id, targetId, inputMode, inputs, outputs, position,
                    scopeMode, contextMode, contextGroupKey, nodeType, includeTaskRepositories, List.copyOf(workspaceRepositoryIds));
        }
    }
}
