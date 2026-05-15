package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-05-15T19:38:29+0300",
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
    public StartForgeResponseDTO asStartForgeResponseDto(Ticket src) {
        if ( src == null ) {
            return null;
        }

        StartForgeResponseDTO.StartForgeResponseDTOBuilder startForgeResponseDTO = StartForgeResponseDTO.builder();

        startForgeResponseDTO.id( src.getId() );
        startForgeResponseDTO.ticket( src.getTicketKey() );
        startForgeResponseDTO.task( src.getTaskDescription() );

        startForgeResponseDTO.scope( "forgeai" );
        startForgeResponseDTO.status( src.getStatus() == null ? null : src.getStatus().name() );
        startForgeResponseDTO.createdAt( toOffsetDateTime(src.getCreatedAt()) );

        return startForgeResponseDTO.build();
    }
}
