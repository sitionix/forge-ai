package com.sitionix.forgeagent.api.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.List;
import java.util.UUID;

@JsonDeserialize(builder = NodeRequest.Builder.class)
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

    @JsonPOJOBuilder(withPrefix = "")
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class Builder {
        private UUID id;
        private UUID targetId;
        private String inputMode;
        private List<NodePortRequest> inputs;
        private List<NodePortRequest> outputs;
        private NodePositionRequest position;
        private String scopeMode;
        private String contextMode;
        private String contextGroupKey;
        private String nodeType;
        @JsonSetter(nulls = Nulls.FAIL)
        private Boolean includeTaskRepositories = true;
        @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
        private List<UUID> workspaceRepositoryIds = List.of();

        public NodeRequest build() {
            return new NodeRequest(id, targetId, inputMode, inputs, outputs, position,
                    scopeMode, contextMode, contextGroupKey, nodeType, includeTaskRepositories, List.copyOf(workspaceRepositoryIds));
        }
    }
}
