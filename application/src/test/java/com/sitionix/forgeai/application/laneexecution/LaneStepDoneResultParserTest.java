package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStepDoneResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaneStepDoneResultParserTest {

    private LaneStepDoneResultParser parser;

    @BeforeEach
    void setUp() {
        this.parser = new LaneStepDoneResultParser(new ObjectMapper());
    }

    @Test
    void givenValidJson_whenParse_thenReturnResult() {
        final LaneStepDoneResult result = this.parser.parse("""
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "scope_slicing",
                  "summary": "done",
                  "evidence": {
                    "nested": {
                      "items": [1, 2, 3]
                    }
                  }
                }
                """, "scope_slicing");

        assertThat(result.getStepId()).isEqualTo("scope_slicing");
        assertThat(result.getSummary()).isEqualTo("done");
        assertThat(result.getEvidence()).containsKey("nested");
    }

    @Test
    void givenProseAroundSingleJsonObject_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("""
                Here is the result:
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "scope_slicing",
                  "summary": "done",
                  "evidence": {
                    "detail": "ok"
                  }
                }
                Thanks.
                """, "scope_slicing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single JSON object only");
    }

    @Test
    void givenNoJson_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("done", "scope_slicing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single JSON object only");
    }

    @Test
    void givenMultipleJsonObjects_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("""
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "scope_slicing",
                  "summary": "first",
                  "evidence": {}
                }
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "scope_slicing",
                  "summary": "second",
                  "evidence": {}
                }
                """, "scope_slicing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one JSON object");
    }

    @Test
    void givenMissingType_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("""
                {
                  "stepId": "scope_slicing",
                  "summary": "done",
                  "evidence": {}
                }
                """, "scope_slicing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid LANE_STEP_DONE JSON payload");
    }

    @Test
    void givenWrongType_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("""
                {
                  "type": "X",
                  "stepId": "scope_slicing",
                  "summary": "done",
                  "evidence": {}
                }
                """, "scope_slicing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid type");
    }

    @Test
    void givenWrongStepId_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("""
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "other",
                  "summary": "done",
                  "evidence": {}
                }
                """, "scope_slicing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid stepId");
    }

    @Test
    void givenEmptySummary_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("""
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "scope_slicing",
                  "summary": "",
                  "evidence": {}
                }
                """, "scope_slicing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary must be non-empty");
    }

    @Test
    void givenMissingEvidence_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("""
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "scope_slicing",
                  "summary": "done"
                }
                """, "scope_slicing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid LANE_STEP_DONE JSON payload");
    }

    @Test
    void givenTopLevelStatus_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("""
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "scope_slicing",
                  "summary": "done",
                  "evidence": {},
                  "status": "DONE"
                }
                """, "scope_slicing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid top-level field");
    }

    @Test
    void givenTopLevelFailed_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("""
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "scope_slicing",
                  "summary": "done",
                  "evidence": {},
                  "failed": true
                }
                """, "scope_slicing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid top-level field");
    }

    @Test
    void givenExtraTopLevelField_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("""
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "scope_slicing",
                  "summary": "done",
                  "evidence": {},
                  "x": 1
                }
                """, "scope_slicing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid LANE_STEP_DONE JSON payload");
    }
}
