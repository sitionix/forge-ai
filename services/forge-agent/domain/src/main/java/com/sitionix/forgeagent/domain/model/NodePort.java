package com.sitionix.forgeagent.domain.model;

import java.util.UUID;
import com.sitionix.forgeagent.domain.model.dialogue.DialogueOutputDisposition;

public record NodePort(
        UUID id,
        String name,
        String description,
        int order,
        DialogueOutputDisposition dialogueDisposition
) {
    public NodePort(UUID id, String name, String description, int order) { this(id, name, description, order, null); }
}
