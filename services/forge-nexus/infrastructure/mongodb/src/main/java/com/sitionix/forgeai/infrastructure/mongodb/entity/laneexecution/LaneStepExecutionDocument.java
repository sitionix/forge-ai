package com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@AllArgsConstructor
@Document(collection = "lane_step_executions")
public class LaneStepExecutionDocument {
    @Id
    private UUID id;
    private UUID executionId;
    private String stepId;
    private int stepOrder;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private boolean done;
    private String resultJson;
    private String evidenceJson;
}
