package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.AssetCapabilities;
import com.sitionix.forgeagent.domain.model.AssetMetrics;
import com.sitionix.forgeagent.domain.model.SshConnection;

/** Typed, read-only host inspection. Implementations expose no arbitrary-command surface. */
public interface AssetInspectionPort {
  AssetMetrics metrics(SshConnection connection);
  AssetCapabilities capabilities(SshConnection connection);
}
