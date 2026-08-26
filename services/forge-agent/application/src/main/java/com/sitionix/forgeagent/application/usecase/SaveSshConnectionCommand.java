package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.SshAuthType;

public record SaveSshConnectionCommand(
        String name,
        String host,
        int port,
        String username,
        SshAuthType authType,
        String privateKeyPath,
        String password) {
    public SaveSshConnectionCommand(
            final String name,
            final String host,
            final int port,
            final String username,
            final String privateKeyPath) {
        this(name, host, port, username, SshAuthType.PRIVATE_KEY, privateKeyPath, null);
    }
}
