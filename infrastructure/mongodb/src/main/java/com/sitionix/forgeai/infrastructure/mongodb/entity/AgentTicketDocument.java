package com.sitionix.forgeai.infrastructure.mongodb.entity;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "agent_tickets")
public class AgentTicketDocument {

    @Id
    private UUID id;

    private UUID ticketId;

    private UUID laneId;

    private AgentTicketStatus status;
    private String scope;
    private Agent agent;

    private AgentTicketPayload payload;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
