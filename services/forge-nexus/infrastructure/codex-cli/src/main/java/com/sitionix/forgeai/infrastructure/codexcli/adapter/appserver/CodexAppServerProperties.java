package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "forge.ai.codex.app-server")
public class CodexAppServerProperties {

    private List<String> command = List.of("codex", "app-server", "--stdio");
    private String clientName = "forge_ai";
    private String clientTitle = "Forge AI";
    private String clientVersion = "0.0.1";
    private boolean experimentalApi = true;
    private boolean requestAttestation = false;
    private String model;
    private String modelProvider;
    private String approvalPolicy = "never";
    private String sandbox = "workspace-write";
    private String serviceName = "forge_ai";
}
