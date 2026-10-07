package com.sitionix.forgeagent.application.dialogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.runtime.JsonSchemaEmbedding;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;

final class DialogueReplyContract {
    private static final String DRAFT_POINTER = "#/$defs/businessDraft";
    private static final String ENVELOPE = """
            {"type":"object","additionalProperties":false,
             "required":["message","draft","questions","decisions","sources","readyForReview"],
             "properties":{
              "message":{"type":"string","minLength":1},
              "draft":{"anyOf":[{"$ref":"#/$defs/businessDraft"},{"type":"null"}]},
              "readyForReview":{"type":"boolean"},
              "questions":{"type":"array","items":{"type":"object","additionalProperties":false,
                "required":["id","text","reason","blocking","recommendation"],
                "properties":{"id":{"type":"string","minLength":1},"text":{"type":"string","minLength":1},
                  "reason":{"type":"string","minLength":1},"blocking":{"type":"boolean"},
                  "recommendation":{"type":["string","null"]}}}},
              "decisions":{"type":"array","items":{"type":"object","additionalProperties":false,
                "required":["id","text","userMessageIds"],
                "properties":{"id":{"type":"string","minLength":1},"text":{"type":"string","minLength":1},
                  "userMessageIds":{"type":"array","minItems":1,"uniqueItems":true,"items":{"type":"string"}}}}},
              "sources":{"type":"array","items":{"type":"object","additionalProperties":false,
                "required":["title","uri","revision"],
                "properties":{"title":{"type":"string","minLength":1},"uri":{"type":"string","minLength":1},
                  "revision":{"type":["string","null"]}}}}
             }}
            """;

    private DialogueReplyContract() { }

    static JsonNode schema(final ObjectMapper json, final AgentOutputSchema business) {
        try {
            final ObjectNode envelope = (ObjectNode)json.readTree(ENVELOPE);
            final JsonNode draft = business == null ? json.createObjectNode() : json.readTree(business.jsonObject());
            envelope.putObject("$defs").set("businessDraft",JsonSchemaEmbedding.embed(draft,DRAFT_POINTER));
            return envelope;
        } catch (Exception exception) { throw new IllegalArgumentException("Dialogue business schema is invalid.",exception); }
    }
}
