package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class ForgeAiApiMapper {

    @Mapping(target = "scope", constant = "forge-ai")
    @Mapping(target = "sourceTerminalTty", source = "sourceTerminalTty")
    public abstract ForgeAiStartCommand asForgeAiStartCommand(StartForgeRequestDTO src, String sourceTerminalTty);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "ticket", source = "ticketKey")
    @Mapping(target = "task", source = "taskDescription")
    @Mapping(target = "scope", constant = "forge-ai")
    @Mapping(target = "status", expression = "java(src.getStatus() == null ? null : src.getStatus().name())")
    @Mapping(target = "createdAt", expression = "java(toOffsetDateTime(src.getCreatedAt()))")
    public abstract StartForgeResponseDTO asStartForgeResponseDto(Ticket src);

    public OffsetDateTime toOffsetDateTime(final LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atOffset(ZoneOffset.UTC);
    }
}
