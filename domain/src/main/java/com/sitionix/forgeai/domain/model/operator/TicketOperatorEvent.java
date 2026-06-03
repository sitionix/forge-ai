package com.sitionix.forgeai.domain.model.operator;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class TicketOperatorEvent {
    UUID ticketId;
    String ticketKey;
    UUID laneId;
    UUID executionId;
    String agentId;
    String scope;
    String stepId;
    String stepTitle;
    Integer stepOrder;
    Integer totalSteps;
    Long codexProcessPid;
    String codexSessionId;
    String codexThreadId;
    String activeTurnId;
    String eventType;
    String message;
    Instant timestamp;
}
