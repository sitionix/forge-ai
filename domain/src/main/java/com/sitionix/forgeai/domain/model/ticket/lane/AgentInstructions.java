package com.sitionix.forgeai.domain.model.ticket.lane;

import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentInstructions {
    private String agentInstruction;
    private Set<String> additionalInstructions;
    private Set<String> sharedInstructions;
}
