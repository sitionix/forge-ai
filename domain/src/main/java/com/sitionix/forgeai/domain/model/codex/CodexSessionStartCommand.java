package com.sitionix.forgeai.domain.model.codex;

import java.util.UUID;
import java.util.List;
import lombok.Builder;

@Builder(toBuilder = true)
public record CodexSessionStartCommand(
        UUID executionId,
        UUID ticketId,
        UUID laneId,
        String workspaceRoot,
        List<String> runtimeWorkspaceRoots,
        String sourceTerminalTty,
        String ticketKey,
        String agentId,
        String scope
) {
}
