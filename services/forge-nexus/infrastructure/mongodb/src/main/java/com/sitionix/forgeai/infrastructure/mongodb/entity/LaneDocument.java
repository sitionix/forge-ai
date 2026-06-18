package com.sitionix.forgeai.infrastructure.mongodb.entity;

import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LaneDocument {
    private UUID id;
    private Agent type;
    private String scope;
    private String serviceId;
    private LaneStatus status;
    private int attempt;
    private Set<UUID> inputTaskIds;
    private LinkedHashSet<LaneDependencyDocument> dependsOn;
}
