package com.sitionix.forgeai.infrastructure.resources.lanestrategy;

import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyPromptConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "forge.ai.lane-strategies")
public class LaneStrategiesProperties implements LaneStrategyPromptConfig {

    private List<String> commonInstructionRefs = new ArrayList<>();
    private Map<String, StrategyConfig> configs = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class StrategyConfig {
        private int version;
        private String sessionMode;
        private List<StepConfig> steps = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class StepConfig {
        private String id;
        private String title;
        private String taskPlaceholder;
        private List<String> instructionRefs = new ArrayList<>();
    }
}
