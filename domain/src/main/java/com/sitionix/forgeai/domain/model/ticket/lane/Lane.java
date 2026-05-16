package com.sitionix.forgeai.domain.model.ticket.lane;

import lombok.Builder;
import lombok.Data;

import java.util.Set;
import java.util.UUID;

@Data
@Builder
public class Lane {
    private UUID id;
    private Agent agent;
    private String scope;
    private String serviceId;
    private LaneStatus status;
    private int attempt;
    private UUID inputTaskId;
    private Set<LaneDependency> dependsOn;
}
