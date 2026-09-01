package com.sitionix.forgeai.domain.model.agentproxy;
import java.util.UUID;
public record CreateAgentProjectAssetCommand(String name, UUID sshConnectionId) {}
