package com.sitionix.forgeai.domain.model.ticket;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTicket<P extends AgentTicketPayload> {

    private UUID id;
    private UUID ticketId;

    private UUID laneId;
    private AgentTicketStatus status;

    private P payload;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
