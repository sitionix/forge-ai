package com.sitionix.forgeai.domain.model.ticket.agentticket;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventPayload implements AgentTicketPayload {
    private Boolean required;
    private String reason;
    private String scope;
    private String summary;
    private String eventName;
    private List<Object> payloadFields;
    private Set<String> consumers;
    private Set<String> notes;
}
