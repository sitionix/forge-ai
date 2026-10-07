package com.sitionix.forgeagent.application.graph;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodeType;
import com.sitionix.forgeagent.domain.model.dialogue.DialogueOutputDisposition;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class DialogueNodePolicy {
    private DialogueNodePolicy() { }

    static void validate(final Node node) {
        final List<NodePort> inputs = node.inputs() == null ? List.of() : node.inputs();
        final List<NodePort> outputs = node.outputs() == null ? List.of() : node.outputs();
        if (inputs.stream().anyMatch(p -> p != null && p.dialogueDisposition() != null)) {
            throw invalid("Dialogue dispositions are only allowed on outputs.");
        }
        if (node.nodeType() != NodeType.DIALOGUE) {
            if (outputs.stream().anyMatch(p -> p != null && p.dialogueDisposition() != null)) {
                throw invalid("Dialogue dispositions require a Dialogue node.");
            }
            return;
        }
        final Set<DialogueOutputDisposition> dispositions = new HashSet<>();
        for (final NodePort port : outputs) {
            if (port == null || port.dialogueDisposition() == null || !dispositions.add(port.dialogueDisposition())) {
                throw invalid("Dialogue requires unique, explicit output dispositions.");
            }
        }
        if (!dispositions.contains(DialogueOutputDisposition.ACCEPT)) {
            throw invalid("Dialogue requires exactly one ACCEPT output.");
        }
    }

    private static ValidationException invalid(final String message) {
        return new ValidationException("INVALID_DIALOGUE_OUTPUTS", message);
    }
}
