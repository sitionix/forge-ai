package com.sitionix.forgeai.domain.model;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.EventPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFePayload;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class CompleteArchitectLaneCommand {
    private UUID sourceLaneId;
    private AgentTicket<ImplementBePayload> implementBeTicket;
    private AgentTicket<ImplementFePayload> implementFeTicket;
    private AgentTicket<ApiPayload> apiTicket;
    private AgentTicket<EventPayload> eventTicket;
    private Boolean apiRequired;
    private String apiScope;
    private Boolean eventRequired;
    private String eventScope;
}
