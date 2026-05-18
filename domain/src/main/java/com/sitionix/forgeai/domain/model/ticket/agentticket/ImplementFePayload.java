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
public class ImplementFePayload implements AgentTicketPayload {
    private String task;
    private String scope;
    private String summary;
    private Set<String> requirements;
    private Set<String> constraints;
    private Set<String> nonGoals;
    private String architectureDecision;
    private Set<String> dependencies;
    private Set<String> acceptanceNotes;
    private Set<String> risks;
}
