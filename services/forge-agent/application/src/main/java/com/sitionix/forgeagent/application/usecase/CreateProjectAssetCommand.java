package com.sitionix.forgeagent.application.usecase;

import java.util.UUID;

public record CreateProjectAssetCommand(String name, UUID sshConnectionId) {}
