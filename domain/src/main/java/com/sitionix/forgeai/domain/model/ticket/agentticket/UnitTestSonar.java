package com.sitionix.forgeai.domain.model.ticket.agentticket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnitTestSonar {

    private Double coveragePercent;
    private Integer issues;
}
