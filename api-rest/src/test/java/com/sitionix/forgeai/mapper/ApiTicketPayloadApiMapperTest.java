package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.ArchitectApiRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectApiOperation;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTicketPayloadApiMapperTest {

    private ApiTicketPayloadApiMapper apiTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.apiTicketPayloadApiMapper = new ApiTicketPayloadApiMapperImpl();
    }

    @Test
    void givenArchitectApiRequest_whenAsApiPayload_thenMapFields() {
        //given
        final ArchitectApiRequest source = ArchitectApiRequest.builder()
                .required(Boolean.TRUE)
                .reason("required")
                .scope("GLOBAL")
                .summary("api summary")
                .operations(List.of(ArchitectApiOperation.builder().intent("create").build()))
                .consumers(List.of("consumer"))
                .notes(List.of("note"))
                .build();

        //when
        final ApiPayload actual = this.apiTicketPayloadApiMapper.asApiPayload(source);

        //then
        assertThat(actual).isEqualTo(ApiPayload.builder()
                .required(Boolean.TRUE)
                .reason("required")
                .scope("GLOBAL")
                .summary("api summary")
                .operations(List.of(ArchitectApiOperation.builder().intent("create").build()))
                .consumers(Set.of("consumer"))
                .notes(Set.of("note"))
                .build());
    }
}
