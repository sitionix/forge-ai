package com.sitionix.forgeagent.application.dialogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DialogueCompletionPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DialogueCompletionPolicy policy = new DialogueCompletionPolicy(json,new DialogueTurnResultPolicy(json));
    private final AgentOutputSchema business = new AgentOutputSchema("{\"type\":\"object\"}");
    private static final String REPLY = "{\"message\":\"Task\",\"draft\":{\"title\":\"Task\"},\"questions\":[],\"decisions\":[],\"sources\":[],\"readyForReview\":true}";

    @Test void acceptRequiresExactCurrentSummary() {
        var snapshot = snapshot(REPLY,DialogueTurnKind.SUMMARY);
        assertThat(policy.validate(business,snapshot,snapshot.summaryRevisionId(),DialogueOutputDisposition.ACCEPT,Set.of())).isNotNull();
        assertThatThrownBy(() -> policy.validate(business,snapshot,UUID.randomUUID(),DialogueOutputDisposition.ACCEPT,Set.of())).hasMessageContaining("summary");
        var chat = snapshot(REPLY,DialogueTurnKind.CHAT);
        assertThatThrownBy(() -> policy.validate(business,chat,chat.summaryRevisionId(),DialogueOutputDisposition.ACCEPT,Set.of())).hasMessageContaining("summary");
    }
    @Test void acceptRejectsOpenQuestionsWhileReworkAndDeferAllowThem() {
        String blocked = REPLY.replace("\"readyForReview\":true","\"readyForReview\":false")
                .replace("\"questions\":[]","\"questions\":[{\"id\":\"q1\",\"text\":\"Which?\",\"reason\":\"Scope\",\"blocking\":true,\"recommendation\":null}]");
        var snapshot = snapshot(blocked,DialogueTurnKind.SUMMARY);
        assertThatThrownBy(() -> policy.validate(business,snapshot,snapshot.summaryRevisionId(),DialogueOutputDisposition.ACCEPT,Set.of()))
                .hasMessageContaining("ready");
        for (var disposition : new DialogueOutputDisposition[]{DialogueOutputDisposition.REWORK,DialogueOutputDisposition.DEFER}) {
            assertThat(policy.validate(business,snapshot,snapshot.summaryRevisionId(),disposition,Set.of())).isNotNull();
        }
    }
    @Test void allDispositionsRequireNonNullValidDraft() {
        var snapshot = snapshot(REPLY.replace("{\"title\":\"Task\"}","null"),DialogueTurnKind.SUMMARY);
        for (var disposition : DialogueOutputDisposition.values()) {
            assertThatThrownBy(() -> policy.validate(business,snapshot,snapshot.summaryRevisionId(),disposition,Set.of()));
        }
    }

    private DialogueSnapshot snapshot(String reply, DialogueTurnKind kind) {
        UUID node = UUID.randomUUID(), id = UUID.randomUUID();
        var revision = new DialogueRevision(id,node,3,UUID.randomUUID(),kind,1,reply,Instant.EPOCH);
        return new DialogueSnapshot(node,DialogueState.AWAITING_REVIEW,3,id,revision,null,null,1,1,Instant.EPOCH,Instant.EPOCH);
    }
}
