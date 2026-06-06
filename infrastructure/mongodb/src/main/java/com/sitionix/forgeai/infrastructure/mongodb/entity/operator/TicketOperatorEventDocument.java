package com.sitionix.forgeai.infrastructure.mongodb.entity.operator;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ticket_operator_events")
public class TicketOperatorEventDocument {
    @Id
    private UUID id;
    @Indexed
    private UUID ticketId;
    private String ticketKey;
    private UUID laneId;
    private UUID executionId;
    private String agentId;
    private String scope;
    private String stepId;
    private String stepTitle;
    private Integer stepOrder;
    private Integer totalSteps;
    private Long codexProcessPid;
    private String codexSessionId;
    private String codexThreadId;
    private String activeTurnId;
    private String eventType;
    private String message;
    @Indexed
    private Instant timestamp;
}
