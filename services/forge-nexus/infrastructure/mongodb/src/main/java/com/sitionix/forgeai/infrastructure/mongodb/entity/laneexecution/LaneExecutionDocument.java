package com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
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
@Document(collection = "lane_executions")
public class LaneExecutionDocument {
    @Id
    private UUID id;
    private UUID ticketId;
    private UUID laneId;
    private String agentId;
    private String scope;
    private String strategyId;
    private int strategyVersion;
    private LaneExecutionStatus status;
    private String sessionId;
    private String threadId;
    private String activeTurnId;
    private Long processPid;
    private String processCommand;
    private String processCwd;
    private String codexVersion;
    private LocalDateTime processStartedAt;
    private String currentStepId;
    private Integer currentStepOrder;
    private String currentStepTitle;
    private String lastProgressEvent;
    private LocalDateTime lastProgressAt;
    private String lastCodexEventType;
    private LocalDateTime startedAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private LocalDateTime interruptedAt;
    private LocalDateTime failedAt;
    private LocalDateTime cancelRequestedAt;
    private String failureMessage;
    private List<String> stderrTail;
}
