package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.SshAuthType;
import jakarta.validation.constraints.*;

public record SshConnectionRequest(
    @NotBlank String name,
    @NotBlank String host,
    @Min(1) @Max(65535) int port,
    @NotBlank String username,
    @NotNull SshAuthType authType,
    String privateKeyPath,
    String password) {
    public SshConnectionRequest(
            String name, String host, int port, String username, String privateKeyPath) {
        this(name, host, port, username, SshAuthType.PRIVATE_KEY, privateKeyPath, null);
    }
}
