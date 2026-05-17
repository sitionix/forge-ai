package com.sitionix.forgeai.domain.model.ticket;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTicketPayload {
    private UUID id;

    private String task;

    private String summary;

    private String scope;

    private Agent agent;
}
