package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.*;

public interface ServiceProcessMetricsPort {
  ServiceProcessMetricsSnapshot collect(SshConnection connection, String unit, ProcessMetricsSort sort);
}
