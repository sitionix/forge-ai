package com.sitionix.forgeai.infrastructure.mongodb;

import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-05-15T19:38:36+0300",
    comments = "version: 1.6.2, compiler: javac, environment: Java 21.0.9 (Oracle Corporation)"
)
@Component
public class TicketEntityMapperImpl implements TicketEntityMapper {

    @Autowired
    private LaneEntityMapper laneEntityMapper;

    @Override
    public TicketDocument asTicketDocument(Ticket ticket) {
        if ( ticket == null ) {
            return null;
        }

        UUID id = null;
        String ticketKey = null;
        String taskDescription = null;
        TicketStatus status = null;
        List<LaneDocument> lanes = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = ticket.getId();
        ticketKey = ticket.getTicketKey();
        taskDescription = ticket.getTaskDescription();
        status = ticket.getStatus();
        lanes = laneListToLaneDocumentList( ticket.getLanes() );
        createdAt = ticket.getCreatedAt();
        updatedAt = ticket.getUpdatedAt();

        TicketDocument ticketDocument = new TicketDocument( id, ticketKey, taskDescription, status, lanes, createdAt, updatedAt );

        return ticketDocument;
    }

    @Override
    public Ticket asTicket(TicketDocument ticketDocument) {
        if ( ticketDocument == null ) {
            return null;
        }

        Ticket.TicketBuilder ticket = Ticket.builder();

        ticket.id( ticketDocument.getId() );
        ticket.ticketKey( ticketDocument.getTicketKey() );
        ticket.taskDescription( ticketDocument.getTaskDescription() );
        ticket.status( ticketDocument.getStatus() );
        ticket.lanes( laneDocumentListToLaneList( ticketDocument.getLanes() ) );
        ticket.createdAt( ticketDocument.getCreatedAt() );
        ticket.updatedAt( ticketDocument.getUpdatedAt() );

        return ticket.build();
    }

    protected List<LaneDocument> laneListToLaneDocumentList(List<Lane> list) {
        if ( list == null ) {
            return null;
        }

        List<LaneDocument> list1 = new ArrayList<LaneDocument>( list.size() );
        for ( Lane lane : list ) {
            list1.add( laneEntityMapper.asLaneDocument( lane ) );
        }

        return list1;
    }

    protected List<Lane> laneDocumentListToLaneList(List<LaneDocument> list) {
        if ( list == null ) {
            return null;
        }

        List<Lane> list1 = new ArrayList<Lane>( list.size() );
        for ( LaneDocument laneDocument : list ) {
            list1.add( laneEntityMapper.asLane( laneDocument ) );
        }

        return list1;
    }
}
