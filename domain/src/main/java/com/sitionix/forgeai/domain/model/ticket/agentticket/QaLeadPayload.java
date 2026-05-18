package com.sitionix.forgeai.domain.model.ticket.agentticket;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QaLeadPayload implements AgentTicketPayload {

    private Set<String> requirements;
    private Set<String> constraints;
    private Set<String> nonGoals;
    private Set<String> risks;
    private Set<String> dependencies;
    private Set<String> qualityFocus;
    private Set<String> edgeConsiderations;
}
