package com.sitionix.forgeai.domain.model.ticket.lane;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LaneDependency {
    private Agent type;
    private String scope;
}
