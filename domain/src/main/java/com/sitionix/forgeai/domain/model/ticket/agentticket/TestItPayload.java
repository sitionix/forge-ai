package com.sitionix.forgeai.domain.model.ticket.agentticket;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestItPayload implements AgentTicketPayload {
    private String task;
    private String scope;
    private String summary;
    private Set<String> integrationFlows;
    private Set<String> persistenceChanges;
    private Set<String> integrationTestCases;
    private Set<String> unitTestNotes;
}
