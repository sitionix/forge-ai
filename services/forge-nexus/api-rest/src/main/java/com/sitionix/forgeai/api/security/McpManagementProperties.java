package com.sitionix.forgeai.api.security;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="forge.mcp")
public class McpManagementProperties {
    private boolean enabled;
    private Path bootstrapCredentialFile,agentServiceCredentialFile;
    private URI operatorOrigin;
    private Duration sessionTtl=Duration.ofMinutes(30);
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled=value; }
    public Path getBootstrapCredentialFile() { return bootstrapCredentialFile; }
    public void setBootstrapCredentialFile(Path value) { bootstrapCredentialFile=value; }
    public Path getAgentServiceCredentialFile() { return agentServiceCredentialFile; }
    public void setAgentServiceCredentialFile(Path value) { agentServiceCredentialFile=value; }
    public URI getOperatorOrigin() { return operatorOrigin; }
    public void setOperatorOrigin(URI value) { operatorOrigin=value; }
    public Duration getSessionTtl() { return sessionTtl; }
    public void setSessionTtl(Duration value) { sessionTtl=value; }
}
