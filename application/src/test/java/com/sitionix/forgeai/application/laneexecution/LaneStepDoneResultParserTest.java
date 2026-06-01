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
    void givenValidLaneStepDone_whenParse_thenAccept() {
        final String output = """
                {"type":"LANE_STEP_DONE","stepId":"preparation","summary":"ok","evidence":{}}
                """;

        final LaneStepDoneResult result = this.parser.parse(output, "preparation");

        assertThat(result.getStepId()).isEqualTo("preparation");
        assertThat(result.getSummary()).isEqualTo("ok");
        assertThat(result.getEvidence()).isEmpty();
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
                .hasMessageContaining("Invalid LANE_STEP_DONE JSON payload");
    }

    @Test
    void givenForbiddenNegativeField_whenParse_thenReject() {
        assertThatThrownBy(() -> this.parser.parse("{" +
                "\"type\":\"LANE_STEP_DONE\",\"stepId\":\"preparation\",\"summary\":\"ok\",\"evidence\":{},\"failed\":true" +
                "}", "preparation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid LANE_STEP_DONE JSON payload");
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
