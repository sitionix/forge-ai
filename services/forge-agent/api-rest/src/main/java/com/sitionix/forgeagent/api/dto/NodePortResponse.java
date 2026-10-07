package com.sitionix.forgeagent.api.dto;

import java.util.UUID;
import com.sitionix.forgeagent.domain.model.dialogue.DialogueOutputDisposition;

public record NodePortResponse(
        UUID id,
        String name,
        String description,
        int order,
        DialogueOutputDisposition dialogueDisposition
) {
    public NodePortResponse(UUID id,
        String name,
        String description,
        int order) { this(id, name, description, order, null); }
}
