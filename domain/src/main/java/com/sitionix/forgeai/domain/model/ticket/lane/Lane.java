package com.sitionix.forgeai.domain.model.ticket.lane;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashSet;
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
    private Set<UUID> inputTaskIds;
    private LinkedHashSet<LaneDependency> dependsOn;

    public UUID singleInputTaskIdForExecution() {
        final Set<UUID> ids = this.inputTaskIds == null ? Collections.emptySet() : this.inputTaskIds;
        if (ids.size() != 1) {
            throw new IllegalStateException("Expected exactly one input task id for laneId=" + this.id + ", found=" + ids.size());
        }
        return ids.iterator().next();
    }
}
