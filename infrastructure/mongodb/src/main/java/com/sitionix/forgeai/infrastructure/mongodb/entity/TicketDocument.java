package com.sitionix.forgeai.infrastructure.mongodb.entity;

import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "tickets")
@AllArgsConstructor
public class TicketDocument {
    @Id
    private UUID id;
    private String ticketKey;
    private String taskDescription;
    private TicketStatus status;
    private List<LaneDocument> lanes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
