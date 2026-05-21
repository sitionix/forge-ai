package com.sitionix.forgeai.domain.model.ticket.agentticket;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestUiPayload implements AgentTicketPayload {
    private String task;
    private String scope;
    private String summary;
    private Set<QaLeadIntegrationTestCase> integrationTestCases;
    private Set<QaLeadUnitTestNote> unitTestNotes;
}
