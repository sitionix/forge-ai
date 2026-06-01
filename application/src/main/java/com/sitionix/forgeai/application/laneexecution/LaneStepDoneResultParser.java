package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepDoneResult;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LaneStepDoneResultParser {

    private final ObjectMapper objectMapper;
    private final ObjectReader payloadReader;

    public LaneStepDoneResultParser(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.payloadReader = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .readerFor(LaneStepDonePayload.class);
    }

    public LaneStepDoneResult parse(final String output, final String currentStepId) {
        final String json = this.extractJson(output);
        final LaneStepDonePayload payload = this.parsePayload(json);
        final String type = payload.type();
        if (!"LANE_STEP_DONE".equals(type)) {
            throw new IllegalArgumentException("Invalid type: " + type);
        }
        final String stepId = payload.stepId();
        if (!currentStepId.equals(stepId)) {
            throw new IllegalArgumentException("Invalid stepId: " + stepId + " expected=" + currentStepId);
        }
        final String summary = payload.summary();
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must be non-empty");
        }
        final Map<String, Object> evidence = payload.evidence();
        if (evidence == null) {
            throw new IllegalArgumentException("evidence must be object");
        }
        return LaneStepDoneResult.builder()
                .stepId(stepId)
                .summary(summary)
                .evidence(evidence)
                .rawJson(json)
                .build();
    }

    private String extractJson(final String output) {
        final int first = output.indexOf('{');
        final int last = output.lastIndexOf('}');
        if (first < 0 || last <= first) {
            throw new IllegalArgumentException("Missing JSON object in Codex output");
        }
        return output.substring(first, last + 1);
    }

    private LaneStepDonePayload parsePayload(final String json) {
        try {
            return this.payloadReader.readValue(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid LANE_STEP_DONE JSON payload", e);
        }
    }

    private record LaneStepDonePayload(
            @JsonProperty(value = "type", required = true) String type,
            @JsonProperty(value = "stepId", required = true) String stepId,
            @JsonProperty(value = "summary", required = true) String summary,
            @JsonProperty(value = "evidence", required = true) Map<String, Object> evidence
    ) {
    }
}
