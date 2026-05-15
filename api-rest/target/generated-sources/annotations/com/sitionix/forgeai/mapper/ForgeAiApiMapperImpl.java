package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ForgeAiStartTask;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-05-15T12:34:54+0300",
    comments = "version: 1.6.2, compiler: javac, environment: Java 21.0.9 (Oracle Corporation)"
)
@Component
public class ForgeAiApiMapperImpl implements ForgeAiApiMapper {

    @Override
    public ForgeAiStartCommand asForgeAiStartCommand(StartForgeRequestDTO src, String sourceTerminalTty) {
        if ( src == null && sourceTerminalTty == null ) {
            return null;
        }

        ForgeAiStartCommand.ForgeAiStartCommandBuilder forgeAiStartCommand = ForgeAiStartCommand.builder();

        if ( src != null ) {
            forgeAiStartCommand.ticket( src.getTicket() );
            forgeAiStartCommand.task( src.getTask() );
            List<String> list = src.getServiceIds();
            if ( list != null ) {
                forgeAiStartCommand.serviceIds( new ArrayList<String>( list ) );
            }
        }
        forgeAiStartCommand.sourceTerminalTty( sourceTerminalTty );
        forgeAiStartCommand.scope( "forgeai" );

        return forgeAiStartCommand.build();
    }

    @Override
    public StartForgeResponseDTO asStartForgeResponseDto(ForgeAiStartTask src) {
        if ( src == null ) {
            return null;
        }

        StartForgeResponseDTO.StartForgeResponseDTOBuilder startForgeResponseDTO = StartForgeResponseDTO.builder();

        startForgeResponseDTO.ticket( src.getTicket() );
        startForgeResponseDTO.task( src.getTask() );
        startForgeResponseDTO.scope( src.getScope() );
        startForgeResponseDTO.status( src.getStatus() );

        startForgeResponseDTO.id( java.util.UUID.fromString(src.getId()) );
        startForgeResponseDTO.createdAt( toOffsetDateTime(src.getCreatedAt()) );

        return startForgeResponseDTO.build();
    }
}
