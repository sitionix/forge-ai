package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.RuntimeTargetCandidate;
import com.sitionix.forgeagent.domain.model.ServiceRuntimeProvider;
import com.sitionix.forgeagent.domain.model.SshConnection;
import java.util.List;

public interface RuntimeTargetDiscoveryPort {
  List<RuntimeTargetCandidate> discover(SshConnection connection, ServiceRuntimeProvider provider);
}
