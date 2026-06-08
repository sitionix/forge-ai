package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceDefaultMode;
import com.sitionix.forgeai.domain.model.operator.service.OperatorServiceWorkspaceState;

public interface OperatorServiceWorkspacePort {

    OperatorServiceWorkspaceState inspect(String serviceId, String configuredPath, String repository);

    OperatorServiceWorkspaceState cloneRepository(String serviceId, String configuredPath, String repository);

    OperatorServiceWorkspaceState resetToDefaultBranch(
            String serviceId,
            String configuredPath,
            String repository,
            OperatorServiceDefaultMode mode
    );
}
