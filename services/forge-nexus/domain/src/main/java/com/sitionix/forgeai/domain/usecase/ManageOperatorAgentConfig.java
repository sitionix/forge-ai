package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.operator.config.OperatorAgentConfigResponse;
import com.sitionix.forgeai.domain.model.operator.config.OperatorConfigResourceSaveRequest;
import com.sitionix.forgeai.domain.model.operator.config.OperatorConfigResourceView;

public interface ManageOperatorAgentConfig {

    OperatorAgentConfigResponse config();

    OperatorConfigResourceView saveResource(OperatorConfigResourceSaveRequest request);
}
