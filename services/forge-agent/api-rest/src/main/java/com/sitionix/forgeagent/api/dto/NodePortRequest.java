package com.sitionix.forgeagent.api.dto;

import java.util.UUID;
import com.sitionix.forgeagent.domain.model.dialogue.DialogueOutputDisposition;

public record NodePortRequest(
        UUID id,
        String name,
        String description,
        int order,
        DialogueOutputDisposition dialogueDisposition
) {
    public NodePortRequest(UUID id,
        String name,
        String description,
        int order) { this(id, name, description, order, null); }
}
