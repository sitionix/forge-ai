package com.sitionix.forgeagent.application.dialogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.dialogue.DialogueTurnKind;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DialogueTurnResultPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DialogueTurnResultPolicy policy = new DialogueTurnResultPolicy(json);
    private final AgentOutputSchema business = new AgentOutputSchema("{\"type\":\"object\",\"required\":[\"title\"],\"properties\":{\"title\":{\"type\":\"string\"}},\"additionalProperties\":false}");
    private static final String VALID = "{\"message\":\"Draft\",\"draft\":{\"title\":\"Task\"},\"questions\":[],\"decisions\":[],\"sources\":[],\"readyForReview\":true}";

    @Test void summaryRequiresValidNonNullBusinessDraft() {
        assertThat(policy.validate(business,DialogueTurnKind.SUMMARY,VALID,Set.of()).path("draft").path("title").asText()).isEqualTo("Task");
        assertThatThrownBy(() -> policy.validate(business,DialogueTurnKind.SUMMARY,VALID.replace("{\"title\":\"Task\"}","null"),Set.of())).hasMessageContaining("Dialogue");
        assertThatThrownBy(() -> policy.validate(business,DialogueTurnKind.SUMMARY,VALID.replace("{\"title\":\"Task\"}","{}"),Set.of())).hasMessageContaining("Dialogue");
        assertThat(policy.validate(business,DialogueTurnKind.CHAT,VALID.replace("{\"title\":\"Task\"}","null"),Set.of()).path("draft").isNull()).isTrue();
    }
    @Test void blockingQuestionsCannotDeclareReadiness() {
        String blocked = VALID.replace("\"questions\":[]", "\"questions\":[{\"id\":\"q1\",\"text\":\"Which?\",\"reason\":\"Scope\",\"blocking\":true,\"recommendation\":null}]");
        assertThatThrownBy(() -> policy.validate(business,DialogueTurnKind.SUMMARY,blocked,Set.of())).hasMessageContaining("Dialogue");
    }
    @Test void decisionsMustReferenceActualUserMessages() {
        UUID id = UUID.randomUUID();
        String decisions = VALID.replace("\"decisions\":[]","\"decisions\":[{\"id\":\"d1\",\"text\":\"Approved\",\"userMessageIds\":[\""+id+"\"]}]");
        assertThatThrownBy(() -> policy.validate(business,DialogueTurnKind.CHAT,decisions,Set.of())).hasMessageContaining("Dialogue");
        assertThat(policy.validate(business,DialogueTurnKind.CHAT,decisions,Set.of(id))).isNotNull();
    }
    @Test void malformedOrIncompleteEnvelopeDoesNotCreateReply() {
        for (String output : new String[]{"null","[]","{}","not JSON",VALID.replace("\"message\":\"Draft\"","\"message\":\" \"")}) {
            assertThatThrownBy(() -> policy.validate(business,DialogueTurnKind.CHAT,output,Set.of())).hasMessageContaining("Dialogue");
        }
    }
    @Test void externalSchemaReferencesCannotFetchResources() {
        var external = new AgentOutputSchema("{\"$ref\":\"http://127.0.0.1:9/private/schema\"}");
        assertThatThrownBy(() -> policy.validate(external,DialogueTurnKind.SUMMARY,VALID,Set.of())).hasMessageContaining("Dialogue");
    }
    @Test void internalBusinessReferencesSurviveEmbedding() {
        var schema = new AgentOutputSchema("{\"type\":\"object\",\"properties\":{\"title\":{\"$ref\":\"#/$defs/name\"}},\"$defs\":{\"name\":{\"type\":\"string\"}},\"required\":[\"title\"]}");
        assertThat(policy.validate(schema,DialogueTurnKind.SUMMARY,VALID,Set.of())).isNotNull();
        assertThatThrownBy(() -> policy.validate(schema,DialogueTurnKind.SUMMARY,VALID.replace("\"Task\"","7"),Set.of())).hasMessageContaining("Dialogue");
    }
}
