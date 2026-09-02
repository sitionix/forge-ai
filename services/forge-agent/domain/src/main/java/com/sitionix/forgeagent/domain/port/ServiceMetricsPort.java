package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.ServiceMetricsSnapshot;
import com.sitionix.forgeagent.domain.model.SshConnection;

public interface ServiceMetricsPort {
  ServiceMetricsSnapshot collect(SshConnection connection);
}
