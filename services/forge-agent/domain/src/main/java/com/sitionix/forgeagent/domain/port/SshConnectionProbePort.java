package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.SshConnection;

public interface SshConnectionProbePort {
  void test(SshConnection connection);
}
