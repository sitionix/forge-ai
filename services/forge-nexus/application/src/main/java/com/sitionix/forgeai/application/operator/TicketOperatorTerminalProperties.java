package com.sitionix.forgeai.application.operator;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "forge.ai.operator.ticket-terminal")
public class TicketOperatorTerminalProperties {

    private boolean enabled = true;
    private String defaultVerbosity = "minimal";
    private Duration heartbeatInterval = Duration.ofSeconds(5);
    private Duration heartbeatTimeout = Duration.ofSeconds(15);
    private boolean stopOnWindowClose = true;
    private boolean autoOpenOnTicketStart = false;
    private String launcher = "auto";
}
