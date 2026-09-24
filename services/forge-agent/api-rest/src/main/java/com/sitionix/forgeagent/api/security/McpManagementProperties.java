package com.sitionix.forgeagent.api.security;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="forge.mcp")
public class McpManagementProperties {
    private boolean enabled;
    private Path serviceCredentialFile;
    private Path keyFile;
    private Path databaseCredentialFile;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path getServiceCredentialFile() { return serviceCredentialFile; }
    public void setServiceCredentialFile(Path value) { serviceCredentialFile = value; }
    public Path getKeyFile() { return keyFile; }
    public void setKeyFile(Path value) { keyFile = value; }
    public Path getDatabaseCredentialFile() { return databaseCredentialFile; }
    public void setDatabaseCredentialFile(Path value) { databaseCredentialFile = value; }
}
