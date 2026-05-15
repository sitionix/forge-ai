package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ForgeAiStartTask;
import java.time.Instant;
import java.time.OffsetDateTime;
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
        assertThat(actual.getScope()).isEqualTo("forgeai");
        assertThat(actual.getTicket()).isEqualTo("SITIONIX-1");
        assertThat(actual.getTask()).isEqualTo("hi");
        assertThat(actual.getServiceIds()).isEqualTo(List.of("athssox", "forgeai"));
        assertThat(actual.getSourceTerminalTty()).isEqualTo("/dev/ttys008");
    }

    @Test
    void givenStartTask_whenAsStartForgeResponseDto_thenMapFields() {
        //given
        final Instant createdAt = Instant.parse("2026-05-15T08:00:00Z");
        final String id = UUID.randomUUID().toString();
        final ForgeAiStartTask startTask = ForgeAiStartTask.builder()
                .id(id)
                .ticket("SITIONIX-1")
                .task("hi")
                .scope("forgeai")
                .status("SUBMITTED")
                .createdAt(createdAt)
                .build();

        //when
        final StartForgeResponseDTO actual = this.forgeAiApiMapper.asStartForgeResponseDto(startTask);

        //then
        assertThat(actual.getId()).isEqualTo(UUID.fromString(id));
        assertThat(actual.getTicket()).isEqualTo("SITIONIX-1");
        assertThat(actual.getTask()).isEqualTo("hi");
        assertThat(actual.getScope()).isEqualTo("forgeai");
        assertThat(actual.getStatus()).isEqualTo("SUBMITTED");
        assertThat(actual.getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }
}
