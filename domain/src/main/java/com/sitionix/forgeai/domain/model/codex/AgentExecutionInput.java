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
    private UUID laneId;
    private P payload;
    private String agentInstruction;
    private ScopeContext scope;
    private ForgeAiContractApi contractApi;
    private Set<String> additionalInstructions;
    private Set<String> sharedInstructions;
}
