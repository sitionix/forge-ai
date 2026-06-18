package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "forge.ai.codex.progress")
public class CodexProgressProperties {

    private boolean enabled = true;
    private boolean logAgentMessageDeltas = false;
    private boolean logCommandOutputDeltas = true;
    private boolean logPrompts = false;
    private boolean logReasoningSummaries = false;
    private int commandOutputMaxCharsPerLine = 2000;
    private int agentMessageMaxCharsPerLine = 1000;
    private Duration heartbeatInterval = Duration.ofSeconds(30);
}
