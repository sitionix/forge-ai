package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBeChangedFileDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUnitPayload;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestUnitTicketPayloadApiMapperTest {

    private TestUnitTicketPayloadApiMapper testUnitTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.testUnitTicketPayloadApiMapper = new TestUnitTicketPayloadApiMapperImpl();
    }

    @Test
    void givenCompleteImplementBeLaneRequest_whenAsTestUnitPayload_thenMapFields() {
        //given
        final CompleteImplementBeLaneRequestDTO source = CompleteImplementBeLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("summary")
                .changedFiles(List.of(
                        ImplementBeChangedFileDTO.builder().path("a.java").reason("r1").build(),
                        ImplementBeChangedFileDTO.builder().path("b.java").reason("r2").build()
                ))
                .build();

        //when
        final TestUnitPayload actual = this.testUnitTicketPayloadApiMapper.asTestUnitPayload(source);

        //then
        assertThat(actual).isEqualTo(this.getExpectedPayload());
    }

    private TestUnitPayload getExpectedPayload() {
        final TestUnitPayload payload = new TestUnitPayload();
        payload.setTask("Write unit tests for backend changed files in automationservice-sox");
        payload.setScope("automationservice-sox");
        payload.setSummary("summary");
        payload.setChangedFiles(Set.of("a.java :: r1", "b.java :: r2"));
        return payload;
    }
}
