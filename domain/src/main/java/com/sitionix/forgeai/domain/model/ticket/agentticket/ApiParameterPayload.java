package com.sitionix.forgeai.domain.model.ticket.agentticket;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiParameterPayload implements AgentTicketPayload {

    private String name;
    private String in;
    private Boolean required;
    private String type;
    private String description;
}
