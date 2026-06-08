package com.sitionix.forgeai.domain.model.laneexecution;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LaneStrategyStep {
    String id;
    String title;
    int order;
    LaneStrategyStepType type;
    String handler;
    String taskPlaceholder;
    String completionContractPlaceholder;
    String validator;
    List<String> instructionRefs;

    public boolean isOrchestratorStep() {
        return LaneStrategyStepType.ORCHESTRATOR.equals(this.type);
    }
}
