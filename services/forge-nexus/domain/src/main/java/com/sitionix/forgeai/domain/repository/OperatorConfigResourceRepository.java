package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.operator.OperatorConfigResource;
import java.util.List;

public interface OperatorConfigResourceRepository {

    OperatorConfigResource agentYaml();

    OperatorConfigResource laneStrategiesYaml();

    OperatorConfigResource instruction(String instructionRef);

    OperatorConfigResource contract(String payloadType);

    List<OperatorConfigResource> contracts();

    OperatorConfigResource save(String resourceKey, String content);
}
