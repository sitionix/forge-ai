package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.ArchitectEventRequest;
import com.app_afesox.fgaisox.api_first.dto.ArchitectEventPayloadField;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayloadField;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventTicketPayloadApiMapperTest {

    private EventTicketPayloadApiMapper eventTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.eventTicketPayloadApiMapper = new EventTicketPayloadApiMapperImpl();
    }

    @Test
    void givenArchitectEventRequest_whenAsEventPayload_thenMapFields() {
        //given
        final ArchitectEventRequest source = ArchitectEventRequest.builder()
                .required(Boolean.TRUE)
                .reason("required")
                .scope("GLOBAL")
                .summary("event summary")
                .eventName("AgentActionCreated")
                .payloadFields(List.of(ArchitectEventPayloadField.builder().name("id").type("string").required(Boolean.TRUE).build()))
                .consumers(List.of("consumer"))
                .notes(List.of("note"))
                .build();

        //when
        final EventPayload actual = this.eventTicketPayloadApiMapper.asEventPayload(source);

        //then
        assertThat(actual).isEqualTo(EventPayload.builder()
                .required(Boolean.TRUE)
                .reason("required")
                .scope("GLOBAL")
                .summary("event summary")
                .eventName("AgentActionCreated")
                .payloadFields(Set.of(EventPayloadField.builder().name("id").type("string").required(Boolean.TRUE).build()))
                .consumers(Set.of("consumer"))
                .notes(Set.of("note"))
                .build());
    }
}
