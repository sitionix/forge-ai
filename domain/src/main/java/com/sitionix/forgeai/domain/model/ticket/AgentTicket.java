package com.sitionix.forgeai.domain.model.ticket;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTicket<P extends AgentTicketPayload> {

    private UUID id;
    private UUID ticketId;

    private UUID sourceLaneId;

    private UUID laneId;
    private AgentTicketStatus status;
    private String scope;
    private Agent agent;

    private P payload;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
