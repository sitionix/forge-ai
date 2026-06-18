package com.sitionix.forgeai.domain.model.ticket.agentticket;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewerPayload implements AgentTicketPayload {

    private String task;
    private String scope;
    private String summary;
    private List<String> affectedFiles;
    private UnitTestSonar sonar;
}
