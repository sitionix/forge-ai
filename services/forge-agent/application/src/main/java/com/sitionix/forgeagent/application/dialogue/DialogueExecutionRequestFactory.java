package com.sitionix.forgeagent.application.dialogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import com.sitionix.forgeagent.domain.port.ConnectionResolutionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DialogueExecutionRequestFactory {
    private final ObjectMapper json;
    private final DialogueTranscript transcript;
    private final NodeInputContentPolicyRegistry inputPolicies;
    private final ConnectionResolutionRepository resolutions;
    private final DialogueTurnResultPolicy results;
    private final DialogueProperties properties;

    public Prepared prepare(final DialogueInvocationLock.Target target, final DialogueTurn turn) {
        final NodeRun node = target.node();
        final var envelope = inputPolicies.assemble(new NodeInputContentContext(target.run(),node,
                resolutions.findConsumedByNodeRunId(node.id()))).envelope();
        final var input = json.createObjectNode().put("contractVersion",1).put("turnKind",turn.kind().name())
                .put("dialogueTurnId",turn.id().toString()).put("revision",turn.inputRevision());
        input.set("upstreamContext",NodeExecutionInputJson.build(json,envelope,List.of()));
        if (turn.triggeringMessageId() == null) input.putNull("triggeringMessageId");
        else input.put("triggeringMessageId",turn.triggeringMessageId().toString());
        final var messages = input.putArray("transcript");
        for (var message : transcript.messages(node.id())) {
            messages.addObject().put("id",message.id().toString()).put("sequence",message.sequence())
                    .put("role",message.role().name()).put("text",message.text());
        }
        final String requestJson = input.toString();
        if (turn.kind() == DialogueTurnKind.INITIAL
                && requestJson.codePointCount(0,requestJson.length()) > properties.maxInitialInputCodePoints()) {
            throw new ValidationException("DIALOGUE_INITIAL_INPUT_TOO_LARGE", "Dialogue initial context exceeds its configured limit.");
        }
        return new Prepared(envelope,results.replySchema(node.agentOutputSchema()),
                new DialogueExecutionContext(turn.id(),turn.kind(),turn.inputRevision(),requestJson));
    }

    public DialogueExecutionRequest bind(final DialogueInvocationLock.Target target, final Prepared prepared,
                                         final ExecutionWorkspace workspace, final AgentSessionExecutionClaim session) {
        final NodeRun node = target.node();
        return new DialogueExecutionRequest(new NodeExecutionClaim(target.run().id(),node.id(),node.sourceAgentId(),
                target.run().input(),node.agentName(),node.agentInstructions(),prepared.schema(),node.executionModel(),
                prepared.envelope(),List.of(),workspace,session,prepared.context()));
    }

    public record Prepared(NodeInputEnvelope envelope, AgentOutputSchema schema, DialogueExecutionContext context) { }
}
