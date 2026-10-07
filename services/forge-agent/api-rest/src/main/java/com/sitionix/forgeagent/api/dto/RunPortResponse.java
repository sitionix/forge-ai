package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.PortDirection;
import java.util.UUID;
import com.sitionix.forgeagent.domain.model.dialogue.DialogueOutputDisposition;

public record RunPortResponse(
        UUID sourcePortId,
        UUID sourceNodeId,
        PortDirection direction,
        String name,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String description,
        int order,
        DialogueOutputDisposition dialogueDisposition
) {
    public RunPortResponse(UUID sourcePortId,
        UUID sourceNodeId,
        PortDirection direction,
        String name,
        int order) { this(sourcePortId, sourceNodeId, direction, name, null, order, null); }
    public RunPortResponse(UUID sourcePortId, UUID sourceNodeId, PortDirection direction,
            String name, int order, DialogueOutputDisposition dialogueDisposition) {
        this(sourcePortId, sourceNodeId, direction, name, null, order, dialogueDisposition);
    }
}
