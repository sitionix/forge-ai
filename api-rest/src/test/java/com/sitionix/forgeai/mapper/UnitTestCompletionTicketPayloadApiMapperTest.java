package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteUnitTestLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.UnitTestSonarDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ReviewerPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.UnitTestSonar;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnitTestCompletionTicketPayloadApiMapperTest {

    private UnitTestCompletionTicketPayloadApiMapper unitTestCompletionTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.unitTestCompletionTicketPayloadApiMapper = new UnitTestCompletionTicketPayloadApiMapperImpl();
    }

    @Test
    void givenCompleteUnitTestLaneRequestDTO_whenAsReviewerPayload_thenMapFields() {
        //given
        final UnitTestSonarDTO sonarDto = UnitTestSonarDTO.builder()
                .coveragePercent(91.0)
                .issues(1)
                .build();
        final CompleteUnitTestLaneRequestDTO source = CompleteUnitTestLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("unit summary")
                .affectedFiles(List.of("src/test/java/com/example/FooTest.java"))
                .sonar(sonarDto)
                .build();
        final ReviewerPayload expected = new ReviewerPayload(
                "Prepare reviewer execution context",
                "automationservice-sox",
                "unit summary",
                List.of("src/test/java/com/example/FooTest.java"),
                new UnitTestSonar(91.0, 1)
        );

        //when
        final ReviewerPayload actual = this.unitTestCompletionTicketPayloadApiMapper.asReviewerPayload(source);

        //then
        assertThat(actual).isEqualTo(expected);
    }
}
