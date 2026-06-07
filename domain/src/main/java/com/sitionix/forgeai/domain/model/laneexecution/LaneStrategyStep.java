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
    String taskPlaceholder;
    String completionContractPlaceholder;
    String validator;
    List<String> instructionRefs;
}
