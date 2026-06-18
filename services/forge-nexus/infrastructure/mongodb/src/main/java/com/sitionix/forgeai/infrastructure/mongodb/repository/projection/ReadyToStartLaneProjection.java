package com.sitionix.forgeai.infrastructure.mongodb.repository.projection;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReadyToStartLaneProjection {
    private UUID ticketId;
    private String ticketKey;
    private String sourceTerminalTty;
    private UUID laneId;
    private Agent agent;
    private String scope;
    private String serviceId;
    private int attempt;
}
