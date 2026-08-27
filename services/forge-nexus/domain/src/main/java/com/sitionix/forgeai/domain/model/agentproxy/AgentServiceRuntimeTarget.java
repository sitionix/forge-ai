package com.sitionix.forgeai.domain.model.agentproxy;
import java.util.UUID;
public record AgentServiceRuntimeTarget(String connection,UUID sshConnectionId,String provider,String container,String unit) {}
