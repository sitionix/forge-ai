package com.sitionix.forgeagent.application.dialogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.dialogue.DialogueTurnKind;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DialogueTurnResultPolicy {
    private final ObjectMapper json;
    private final SchemaRegistry schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaCacheEnabled(false).resourceLoaders(loaders -> loaders.values(java.util.List::clear)
                    .add(iri -> { throw new IllegalArgumentException("External schema resources are not allowed."); })));

    public AgentOutputSchema replySchema(final AgentOutputSchema business) {
        return new AgentOutputSchema(DialogueReplyContract.providerSchema(json,business).toString());
    }

    public JsonNode validate(final AgentOutputSchema business, final DialogueTurnKind kind,
                             final String resultJson, final Set<UUID> userMessageIds) {
        try {
            final JsonNode result = json.readTree(resultJson);
            if (result == null || !schemas.getSchema(DialogueReplyContract.schema(json,business)).validate(result).isEmpty()) throw invalid();
            if (result.path("message").textValue().codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))) throw invalid();
            if (kind == DialogueTurnKind.SUMMARY && result.path("draft").isNull()) throw invalid();
            final Set<String> questionIds = new HashSet<>();
            for (JsonNode question : result.path("questions")) {
                if (!questionIds.add(question.path("id").textValue())) throw invalid();
                if (question.path("blocking").booleanValue() && result.path("readyForReview").booleanValue()) throw invalid();
            }
            final Set<String> decisionIds = new HashSet<>();
            for (JsonNode decision : result.path("decisions")) {
                if (!decisionIds.add(decision.path("id").textValue())) throw invalid();
                for (JsonNode messageId : decision.path("userMessageIds")) {
                    if (!userMessageIds.contains(UUID.fromString(messageId.textValue()))) throw invalid();
                }
            }
            return result;
        } catch (ValidationException invalid) { throw invalid; }
        catch (Exception invalidOutput) { throw invalid(); }
    }

    private static ValidationException invalid() {
        return new ValidationException("INVALID_DIALOGUE_RESULT", "Dialogue response does not satisfy its reply contract.");
    }
}
