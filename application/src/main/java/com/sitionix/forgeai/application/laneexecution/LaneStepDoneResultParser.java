package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepDoneResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class LaneStepDoneResultParser {

    private static final String TYPE_FIELD = "type";
    private static final String STEP_ID_FIELD = "stepId";
    private static final String SUMMARY_FIELD = "summary";
    private static final String EVIDENCE_FIELD = "evidence";
    private static final String EXPECTED_TYPE = "LANE_STEP_DONE";
    private static final Set<String> ALLOWED_FIELDS = Set.of(TYPE_FIELD, STEP_ID_FIELD, SUMMARY_FIELD, EVIDENCE_FIELD);
    private static final Set<String> FORBIDDEN_TOP_LEVEL_FIELDS = Set.of("status", "failed", "blocked", "skipped", "needsFix", "error");

    private final ObjectMapper objectMapper;

    public LaneStepDoneResultParser(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LaneStepDoneResult parse(final String responseText, final String currentStepId) {
        final String json = this.requireStrictJsonObject(responseText);
        final JsonNode root = this.readRoot(json);
        this.validateTopLevelFields(root);
        final String type = this.readRequiredText(root, TYPE_FIELD);
        if (!EXPECTED_TYPE.equals(type)) {
            throw new IllegalArgumentException("Invalid type: " + type);
        }
        final String stepId = this.readRequiredText(root, STEP_ID_FIELD);
        if (!currentStepId.equals(stepId)) {
            throw new IllegalArgumentException("Invalid stepId: " + stepId + " expected=" + currentStepId);
        }
        final String summary = this.readRequiredText(root, SUMMARY_FIELD);
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must be non-empty");
        }
        final JsonNode evidenceNode = root.get(EVIDENCE_FIELD);
        if (evidenceNode == null || !evidenceNode.isObject()) {
            throw new IllegalArgumentException("evidence must be object");
        }
        final Map<String, Object> evidence = this.objectMapper.convertValue(evidenceNode, new TypeReference<Map<String, Object>>() {
        });
        return LaneStepDoneResult.builder()
                .stepId(stepId)
                .summary(summary)
                .evidence(evidence)
                .rawJson(json)
                .build();
    }

    private String requireStrictJsonObject(final String responseText) {
        if (responseText == null || responseText.isBlank()) {
            throw new IllegalArgumentException("Missing assistant response");
        }
        final String trimmed = responseText.trim();
        if (trimmed.startsWith("```") || !trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("Assistant response must be a single JSON object only");
        }
        try {
            final JsonParser parser = this.objectMapper.createParser(trimmed);
            final JsonNode root = this.objectMapper.readTree(parser);
            if (!root.isObject()) {
                throw new IllegalArgumentException("Assistant response must be a JSON object");
            }
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("Assistant response must contain exactly one JSON object");
            }
        } catch (final JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid LANE_STEP_DONE JSON payload", e);
        } catch (final java.io.IOException e) {
            throw new IllegalArgumentException("Invalid LANE_STEP_DONE JSON payload", e);
        }
        return trimmed;
    }

    private JsonNode readRoot(final String json) {
        try {
            return this.objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid LANE_STEP_DONE JSON payload", e);
        }
    }

    private void validateTopLevelFields(final JsonNode root) {
        if (!root.isObject()) {
            throw new IllegalArgumentException("Invalid LANE_STEP_DONE JSON payload");
        }
        final Set<String> fieldNames = new LinkedHashSet<>();
        root.fieldNames().forEachRemaining(fieldNames::add);
        for (final String forbidden : FORBIDDEN_TOP_LEVEL_FIELDS) {
            if (fieldNames.contains(forbidden)) {
                throw new IllegalArgumentException("Invalid top-level field: " + forbidden);
            }
        }
        if (!ALLOWED_FIELDS.containsAll(fieldNames) || !fieldNames.containsAll(ALLOWED_FIELDS)) {
            throw new IllegalArgumentException("Invalid LANE_STEP_DONE JSON payload");
        }
    }

    private String readRequiredText(final JsonNode root, final String fieldName) {
        final JsonNode node = root.get(fieldName);
        if (node == null || node.isNull() || !node.isTextual()) {
            throw new IllegalArgumentException("Invalid LANE_STEP_DONE JSON payload");
        }
        return node.asText();
    }
}
