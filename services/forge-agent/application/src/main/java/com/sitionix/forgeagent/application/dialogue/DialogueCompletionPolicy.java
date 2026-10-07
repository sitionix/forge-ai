package com.sitionix.forgeagent.application.dialogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DialogueCompletionPolicy {
    private final ObjectMapper json;
    private final DialogueTurnResultPolicy replies;

    public JsonNode validate(final AgentOutputSchema business, final DialogueSnapshot snapshot,
                             final UUID summaryRevisionId, final DialogueOutputDisposition disposition,
                             final Set<UUID> userMessageIds) {
        if (snapshot.state() != DialogueState.AWAITING_REVIEW || snapshot.activeTurn() != null
                || summaryRevisionId == null || !summaryRevisionId.equals(snapshot.summaryRevisionId())
                || snapshot.latestRevision() == null || !summaryRevisionId.equals(snapshot.latestRevision().id())
                || snapshot.latestRevision().kind() != DialogueTurnKind.SUMMARY
                || snapshot.latestRevision().revision() != snapshot.revision()) {
            throw new ConflictException("DIALOGUE_SUMMARY_STALE", "Completion requires the exact current summary revision.");
        }
        if (disposition == null) throw new ConflictException("INVALID_DIALOGUE_OUTPUT", "A Dialogue output disposition is required.");
        final JsonNode reply;
        try {
            reply = replies.validate(business,DialogueTurnKind.SUMMARY,snapshot.latestRevision().resultJson(),userMessageIds);
        } catch (RuntimeException invalid) {
            throw new ConflictException("DIALOGUE_SUMMARY_INVALID", "The current summary does not contain a valid business draft.");
        }
        if (disposition == DialogueOutputDisposition.ACCEPT && !reply.path("readyForReview").booleanValue()) {
            throw new ConflictException("DIALOGUE_NOT_READY", "The summary is not ready for acceptance; resolve its blocking questions.");
        }
        return reply;
    }

    public NodeRunOutput output(final JsonNode reply, final DialogueCompletion completion, final long summaryRevision,
                                final UUID workflowRunId) {
        final var output = json.createObjectNode().put("contractVersion",1);
        output.set("result",reply.path("draft").deepCopy());
        final var metadata = output.putObject("dialogue").put("disposition",completion.disposition().name())
                .put("summaryRevisionId",completion.summaryRevisionId().toString()).put("revision",summaryRevision)
                .put("acceptedAt",completion.completedAt().toString());
        for (String key : new String[]{"questions","decisions","sources"}) metadata.set(key,reply.path(key).deepCopy());
        metadata.putObject("transcript").put("nodeRunId",completion.nodeRunId().toString())
                .put("uri","/api/v1/workflow-runs/"+workflowRunId+"/node-runs/"+completion.nodeRunId()+"/dialogue/messages");
        return new NodeRunOutput(output.toString());
    }
}
