package com.sitionix.forgeai.application.laneexecution;

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
}
