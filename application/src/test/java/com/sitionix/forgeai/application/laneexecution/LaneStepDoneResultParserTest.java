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
    void givenSentinelWrappedJson_whenParse_thenAccept() {
        final String output = """
                noise before
                <<<LANE_STEP_DONE_JSON>>>
                {"type":"LANE_STEP_DONE","stepId":"preparation","summary":"ok","evidence":{}}
                <<<END_LANE_STEP_DONE_JSON>>>
                noise after
                """;

        final LaneStepDoneResult result = this.parser.parse(output, "preparation");

        assertThat(result.getStepId()).isEqualTo("preparation");
        assertThat(result.getSummary()).isEqualTo("ok");
        assertThat(result.getEvidence()).isEmpty();
    }

    @Test
    void givenNoisyOutputWithValidMarker_whenParse_thenAccept() {
        final String output = """
                logs
                prose
                {"ignored":"object"}
                <<<LANE_STEP_DONE_JSON>>>
                {"type":"LANE_STEP_DONE","stepId":"preparation","summary":"ok","evidence":{"nested":{"items":[1,2,3]}}}
                <<<END_LANE_STEP_DONE_JSON>>>
                """;

        final LaneStepDoneResult result = this.parser.parse(output, "preparation");

        assertThat(result.getEvidence()).containsKey("nested");
    }

    @Test
    void givenFallbackBalancedJson_whenParse_thenAccept() {
        final String output = """
                logs
                {"type":"LANE_STEP_DONE","stepId":"preparation","summary":"ok","evidence":{"items":[1,2,3]}}
                trailing logs
                """;

        final LaneStepDoneResult result = this.parser.parse(output, "preparation");

        assertThat(result.getEvidence()).containsKey("items");
    }

    @Test
    void givenMissingType_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("{" +
                "\"stepId\":\"preparation\",\"summary\":\"ok\",\"evidence\":{}" +
                "}", "preparation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid LANE_STEP_DONE JSON payload");
    }

    @Test
    void givenWrongType_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("{" +
                "\"type\":\"X\",\"stepId\":\"preparation\",\"summary\":\"ok\",\"evidence\":{}" +
                "}", "preparation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid type");
    }

    @Test
    void givenWrongStepId_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("{" +
                "\"type\":\"LANE_STEP_DONE\",\"stepId\":\"pr\",\"summary\":\"ok\",\"evidence\":{}" +
                "}", "preparation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid stepId");
    }

    @Test
    void givenForbiddenStatus_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("{" +
                "\"type\":\"LANE_STEP_DONE\",\"stepId\":\"preparation\",\"summary\":\"ok\",\"evidence\":{},\"status\":\"DONE\"" +
                "}", "preparation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid top-level field");
    }

    @Test
    void givenForbiddenNegativeField_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("{" +
                "\"type\":\"LANE_STEP_DONE\",\"stepId\":\"preparation\",\"summary\":\"ok\",\"evidence\":{},\"failed\":true" +
                "}", "preparation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid top-level field");
    }

    @Test
    void givenAdditionalField_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("{" +
                "\"type\":\"LANE_STEP_DONE\",\"stepId\":\"preparation\",\"summary\":\"ok\",\"evidence\":{},\"x\":1" +
                "}", "preparation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid LANE_STEP_DONE JSON payload");
    }
}
