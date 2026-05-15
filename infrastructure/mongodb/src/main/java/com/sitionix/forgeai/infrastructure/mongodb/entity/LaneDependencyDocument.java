package com.sitionix.forgeai.infrastructure.mongodb.entity;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LaneDependencyDocument {
    private Agent type;
    private String scope;
}
