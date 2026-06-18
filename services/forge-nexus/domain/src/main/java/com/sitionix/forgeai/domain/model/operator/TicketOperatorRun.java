package com.sitionix.forgeai.domain.model.operator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class TicketOperatorRun {
    UUID ticketId;
    String ticketKey;
    TicketOperatorRunStatus status;
    String watcherId;
    LocalDateTime terminalOpenedAt;
    LocalDateTime lastHeartbeatAt;
    boolean stopOnWindowClose;
    LocalDateTime cancelRequestedAt;
    LocalDateTime cancelledAt;
    String interruptReason;
    List<UUID> activeExecutionIds;
    List<UUID> activeLaneIds;
    String lastProgressEvent;
    LocalDateTime lastProgressAt;
}
