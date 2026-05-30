package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepDoneResult;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LaneStepDoneResultParser {

    private static final Set<String> ALLOWED_FIELDS = Set.of("type", "stepId", "summary", "evidence");
    private static final Set<String> FORBIDDEN_FIELDS = Set.of("status", "failed", "blocked", "skipped", "error", "needsFix", "stalled");
    private final ObjectMapper objectMapper;

    public LaneStepDoneResult parse(final String output, final String currentStepId) {
        final JsonNode node = this.extractJson(output);
        this.validateFields(node);
        final String type = this.requiredText(node, "type");
        if (!"LANE_STEP_DONE".equals(type)) {
            throw new IllegalArgumentException("Invalid type: " + type);
        }
        final String stepId = this.requiredText(node, "stepId");
        if (!currentStepId.equals(stepId)) {
            throw new IllegalArgumentException("Invalid stepId: " + stepId + " expected=" + currentStepId);
        }
        final String summary = this.requiredText(node, "summary");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must be non-empty");
        }
        final JsonNode evidence = node.get("evidence");
        if (evidence == null || !evidence.isObject()) {
            throw new IllegalArgumentException("evidence must be object");
        }
        final Map<String, Object> evidenceMap = this.objectMapper.convertValue(evidence, Map.class);
        try {
            return LaneStepDoneResult.builder()
                    .stepId(stepId)
                    .summary(summary)
                    .evidence(evidenceMap)
                    .rawJson(this.objectMapper.writeValueAsString(node))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize done result", e);
        }
    }

    private JsonNode extractJson(final String output) {
        final int first = output.indexOf('{');
        final int last = output.lastIndexOf('}');
        if (first < 0 || last <= first) {
            throw new IllegalArgumentException("Missing JSON object in Codex output");
        }
        try {
            return this.objectMapper.readTree(output.substring(first, last + 1));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON in Codex output", e);
        }
    }

    private void validateFields(final JsonNode node) {
        final Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            final String field = names.next();
            if (FORBIDDEN_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Forbidden field present: " + field);
            }
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unexpected field present: " + field);
            }
        }
        for (String required : ALLOWED_FIELDS) {
            if (!node.has(required)) {
                throw new IllegalArgumentException("Missing required field: " + required);
            }
        }
    }

    private String requiredText(final JsonNode node, final String field) {
        final JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be string");
        }
        return value.asText();
    }
}
