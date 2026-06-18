package com.sitionix.forgeai.domain.model.laneexecution;

import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LaneStepDoneResult {
    String stepId;
    String summary;
    Map<String, Object> evidence;
    String rawJson;
}
