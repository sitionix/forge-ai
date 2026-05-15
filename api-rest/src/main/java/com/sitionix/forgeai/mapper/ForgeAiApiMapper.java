package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ForgeAiStartTask;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ForgeAiApiMapper {

    @Mapping(target = "scope", constant = "forgeai")
    @Mapping(target = "sourceTerminalTty", source = "sourceTerminalTty")
    ForgeAiStartCommand asForgeAiStartCommand(StartForgeRequestDTO src, String sourceTerminalTty);

    @Mapping(target = "id", expression = "java(java.util.UUID.fromString(src.getId()))")
    @Mapping(target = "createdAt", expression = "java(toOffsetDateTime(src.getCreatedAt()))")
    StartForgeResponseDTO asStartForgeResponseDto(ForgeAiStartTask src);

    default OffsetDateTime toOffsetDateTime(final java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
