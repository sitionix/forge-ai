package com.sitionix.forgeai.domain.model.codex;

import java.util.Set;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionInput<P> {
    private UUID ticketId;
    private String ticket;
    private UUID laneId;
    private Set<P> tasks;
    private String agentInstruction;
    private ScopeContext scope;
    private Set<String> additionalInstructions;
    private Set<String> sharedInstructions;
}
