package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceRuntimeState;

public interface OperatorServiceRuntimePort {

    OperatorServiceRuntimeState healthcheck(String healthcheckUrl);

    OperatorServiceRuntimeState container(String expectedName);
}
