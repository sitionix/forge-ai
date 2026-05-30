package com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
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
    private String sessionId;
    private String currentStepId;
    private LocalDateTime startedAt;
    private LocalDateTime updatedAt;
}
