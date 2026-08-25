package com.sitionix.forgeagent.api.dto;

import jakarta.validation.constraints.*;

public record SshConnectionRequest(
    @NotBlank String name,
    @NotBlank String host,
    @Min(1) @Max(65535) int port,
    @NotBlank String username,
    @NotBlank String privateKeyPath) {}
