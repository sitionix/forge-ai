package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.model.SystemdLogConfiguration;

public interface SystemdLogPort {
  void validate(SystemdLogConfiguration configuration, SshConnection connection);
  LogStream stream(SystemdLogConfiguration configuration, int initialLines, SshConnection connection);
}
