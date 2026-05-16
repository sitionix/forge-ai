package com.sitionix.forgeai.domain.model.ticket.lane;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadyToStartLane {
    private UUID ticketId;
    private String ticketKey;
    private String sourceTerminalTty;
    private UUID laneId;
    private Agent agent;
    private String scope;
    private String serviceId;
    private int attempt;
}
