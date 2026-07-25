package com.sitionix.forgeai.domain.model.laneexecution;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class LaneExecution {
    UUID id;
    UUID ticketId;
    UUID laneId;
    String agentId;
    String scope;
    String strategyId;
    int strategyVersion;
    LaneExecutionStatus status;
    String sessionId;
    String threadId;
    String activeTurnId;
    Long processPid;
    String processCommand;
    String processCwd;
    String codexVersion;
    LocalDateTime processStartedAt;
    String currentStepId;
    Integer currentStepOrder;
    String currentStepTitle;
    String lastProgressEvent;
    LocalDateTime lastProgressAt;
    String lastCodexEventType;
    LocalDateTime startedAt;
    LocalDateTime updatedAt;
    LocalDateTime completedAt;
    LocalDateTime interruptedAt;
    LocalDateTime failedAt;
    LocalDateTime cancelRequestedAt;
    String failureMessage;
    List<String> stderrTail;
}
