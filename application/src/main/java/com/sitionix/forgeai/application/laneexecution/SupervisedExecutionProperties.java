package com.sitionix.forgeai.application.laneexecution;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "forge.ai.supervised-execution")
public class SupervisedExecutionProperties {

    private int correctionAttempts = 2;
    private Duration turnTimeout = Duration.ofMinutes(10);
    private Integer outgoingPromptWarningChars;
    private Integer outgoingPromptFailChars;
}
