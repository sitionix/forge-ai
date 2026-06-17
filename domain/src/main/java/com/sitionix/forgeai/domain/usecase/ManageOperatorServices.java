package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceActionResponse;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceDefaultMode;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceDetailResponse;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServicesResponse;

public interface ManageOperatorServices {

    OperatorServicesResponse services();

    OperatorServiceDetailResponse service(String serviceId);

    OperatorServiceActionResponse cloneService(String serviceId);

    OperatorServiceActionResponse defaultService(String serviceId, OperatorServiceDefaultMode mode);
}
