package com.sitionix.forgeai.infrastructure.mongodb.entity.operator;

import com.sitionix.forgeai.domain.model.operator.TicketOperatorRunStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ticket_operator_runs")
public class TicketOperatorRunDocument {
    @Id
    private UUID ticketId;
    private String ticketKey;
    private TicketOperatorRunStatus status;
    private String watcherId;
    private LocalDateTime terminalOpenedAt;
    private LocalDateTime lastHeartbeatAt;
    private boolean stopOnWindowClose;
    private LocalDateTime cancelRequestedAt;
    private LocalDateTime cancelledAt;
    private String interruptReason;
    private List<UUID> activeExecutionIds;
    private List<UUID> activeLaneIds;
    private String lastProgressEvent;
    private LocalDateTime lastProgressAt;
}
