package com.sitionix.forgeai.domain.model.ticket;

import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class Ticket {
    private UUID id;
    private String ticketKey;
    private String taskDescription;
    private String sourceTerminalTty;
    private TicketStatus status;
    private List<Lane> lanes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
