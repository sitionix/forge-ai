package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestUiTicketPayloadApiMapperTest {

    private TestUiTicketPayloadApiMapper testUiTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.testUiTicketPayloadApiMapper = new TestUiTicketPayloadApiMapperImpl();
    }

    @Test
    void givenCompleteImplementFeLaneRequest_whenAsTestUiPayload_thenMapFields() {
        //given
        final CompleteImplementFeLaneRequestDTO source = CompleteImplementFeLaneRequestDTO.builder()
                .scope("sitionix-spa")
                .summary("Implemented frontend flow")
                .build();

        //when
        final TestUiPayload actual = this.testUiTicketPayloadApiMapper.asTestUiPayload(source);

        //then
        final TestUiPayload expected = new TestUiPayload();
        expected.setTask("Write UI tests for implemented frontend changes in sitionix-spa");
        expected.setScope("sitionix-spa");
        expected.setSummary("Implemented frontend flow");
        assertThat(actual).isEqualTo(expected);
    }
}
