package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepDoneResult;
import java.util.LinkedHashSet;
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
    private static final String SENTINEL_START = "<<<LANE_STEP_DONE_JSON>>>";
    private static final String SENTINEL_END = "<<<END_LANE_STEP_DONE_JSON>>>";
    private static final Set<String> ALLOWED_FIELDS = Set.of(TYPE_FIELD, STEP_ID_FIELD, SUMMARY_FIELD, EVIDENCE_FIELD);
    private static final Set<String> FORBIDDEN_TOP_LEVEL_FIELDS = Set.of("status", "failed", "blocked", "skipped", "needsFix", "error");

    private final ObjectMapper objectMapper;

    public LaneStepDoneResultParser(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LaneStepDoneResult parse(final String output, final String currentStepId) {
        final String json = this.extractPayloadJson(output);
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

    private String extractPayloadJson(final String output) {
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("Missing Codex output");
        }
        final String sentinelJson = this.extractSentinelJson(output);
        if (sentinelJson != null) {
            return sentinelJson;
        }
        final String balancedJson = this.extractLastBalancedJsonObject(output);
        if (balancedJson != null) {
            return balancedJson;
        }
        throw new IllegalArgumentException("Missing LANE_STEP_DONE JSON payload in Codex output");
    }

    private String extractSentinelJson(final String output) {
        final int start = output.lastIndexOf(SENTINEL_START);
        if (start < 0) {
            return null;
        }
        final int contentStart = start + SENTINEL_START.length();
        final int end = output.indexOf(SENTINEL_END, contentStart);
        if (end < 0 || end <= contentStart) {
            throw new IllegalArgumentException("Incomplete LANE_STEP_DONE sentinel block");
        }
        return output.substring(contentStart, end).trim();
    }

    private String extractLastBalancedJsonObject(final String output) {
        int candidateStart = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        final StringBuilder candidate = new StringBuilder();
        String lastBalanced = null;
        for (int index = 0; index < output.length(); index++) {
            final char ch = output.charAt(index);
            if (candidateStart < 0) {
                if (ch == '{') {
                    candidateStart = index;
                    candidate.setLength(0);
                    candidate.append(ch);
                    depth = 1;
                    inString = false;
                    escaped = false;
                }
                continue;
            }
            candidate.append(ch);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    lastBalanced = candidate.toString().trim();
                    candidateStart = -1;
                    candidate.setLength(0);
                }
            }
        }
        return lastBalanced;
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
