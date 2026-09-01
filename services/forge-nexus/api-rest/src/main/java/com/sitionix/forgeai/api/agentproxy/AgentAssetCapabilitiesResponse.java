package com.sitionix.forgeai.api.agentproxy;

public record AgentAssetCapabilitiesResponse(
        boolean systemdAvailable,
        boolean dockerAvailable) {
}
