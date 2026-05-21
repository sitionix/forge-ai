package com.sitionix.forgeai.domain.model.ticket.agentticket;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QaLeadIntegrationTestCase implements AgentTicketPayload {
    private String title;
    private QaLeadIntegrationFlow flow;
    private Set<String> given;
    private Set<String> when;
    private Set<String> then;
    private Set<QaLeadDataCheck> dataChecks;
    private String priority;
}
