package com.sitionix.forgeai.domain.model.codex;

import java.util.UUID;
import lombok.Builder;

@Builder(toBuilder = true)
public record CodexSessionStartCommand(
        UUID executionId,
        UUID ticketId,
        UUID laneId,
        String workspaceRoot,
        String sourceTerminalTty,
        String ticketKey,
        String agentId,
        String scope
) {
}
