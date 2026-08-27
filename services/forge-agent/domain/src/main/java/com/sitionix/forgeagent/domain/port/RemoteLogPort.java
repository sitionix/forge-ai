package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.LogProviderConfiguration;
import com.sitionix.forgeagent.domain.model.LogProviderType;
import com.sitionix.forgeagent.domain.model.SshConnection;

public interface RemoteLogPort {
    void validate(SshConnection connection, LogProviderType provider, LogProviderConfiguration configuration);
    LogStream stream(SshConnection connection, LogProviderType provider, LogProviderConfiguration configuration, int initialLines);
}
