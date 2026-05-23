package com.sitionix.forgeai.domain.model.ticket.agentticket;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QaLeadTestUnitPayload implements AgentTicketPayload {
    private String task;
    private String scope;
    private String summary;
    private Set<QaLeadUnitTestNote> unitTestNotes;
}
