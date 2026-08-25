package com.sitionix.forgeagent.infrastructure.codex;

import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "forge.agent.codex.app-server")
public class CodexAppServerProperties {

    private List<String> command = List.of("codex", "app-server", "--stdio");
    private String runtimeCwd;
    private String clientName = "forge_agent";
    private String clientTitle = "Forge Agent";
    private String clientVersion = "0.0.1";
    private boolean experimentalApi = true;
    private boolean requestAttestation = false;
    private Duration requestTimeout = Duration.ofSeconds(15);
    private Duration turnTimeout = Duration.ofMinutes(90);
    private int stdioFrameLimitBytes = 4 * 1024 * 1024;
    private int modelListMaxPages = 20;
    private Duration gracefulTerminateTimeout = Duration.ofSeconds(2);
    private Duration forceKillTimeout = Duration.ofSeconds(2);
}
