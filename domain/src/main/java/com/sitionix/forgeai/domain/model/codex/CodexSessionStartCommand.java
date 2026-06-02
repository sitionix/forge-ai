package com.sitionix.forgeai.domain.model.codex;

import lombok.Builder;

@Builder(toBuilder = true)
public record CodexSessionStartCommand(
        String workspaceRoot,
        String sourceTerminalTty,
        String ticketKey,
        String agentId,
        String scope
) {
}
