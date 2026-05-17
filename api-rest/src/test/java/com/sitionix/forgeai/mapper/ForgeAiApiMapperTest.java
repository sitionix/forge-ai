package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ForgeAiApiMapperTest {

    private ForgeAiApiMapper forgeAiApiMapper;

    @BeforeEach
    void setUp() {
        this.forgeAiApiMapper = new ForgeAiApiMapperImpl();
    }

    @Test
    void givenStartForgeRequestDTO_whenAsForgeAiStartCommand_thenMapFields() {
        //given
        final StartForgeRequestDTO requestDTO = StartForgeRequestDTO.builder()
                .ticket("SITIONIX-1")
                .task("hi")
                .serviceIds(List.of("athssox", "forgeai"))
                .build();

        //when
        final ForgeAiStartCommand actual = this.forgeAiApiMapper.asForgeAiStartCommand(requestDTO, "/dev/ttys008");

        //then
        assertThat(actual.getScope()).isEqualTo("forge-ai");
        assertThat(actual.getTicket()).isEqualTo("SITIONIX-1");
        assertThat(actual.getTask()).isEqualTo("hi");
        assertThat(actual.getServiceIds()).isEqualTo(List.of("athssox", "forgeai"));
        assertThat(actual.getSourceTerminalTty()).isEqualTo("/dev/ttys008");
    }

    @Test
    void givenTicket_whenAsStartForgeResponseDto_thenMapFields() {
        //given
        final UUID id = UUID.randomUUID();
        final LocalDateTime createdAt = LocalDateTime.of(2026, 5, 15, 8, 0, 0);
        final Ticket ticket = Ticket.builder()
                .id(id)
                .ticketKey("SITIONIX-1")
                .taskDescription("hi")
                .status(TicketStatus.IN_PROGRESS)
                .createdAt(createdAt)
                .build();

        //when
        final StartForgeResponseDTO actual = this.forgeAiApiMapper.asStartForgeResponseDto(ticket);

        //then
        assertThat(actual.getId()).isEqualTo(id);
        assertThat(actual.getTicket()).isEqualTo("SITIONIX-1");
        assertThat(actual.getTask()).isEqualTo("hi");
        assertThat(actual.getScope()).isEqualTo("forge-ai");
        assertThat(actual.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(actual.getCreatedAt()).isEqualTo(createdAt.atOffset(ZoneOffset.UTC));
    }
}
