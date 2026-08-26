package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.LogProviderConfiguration;
import com.sitionix.forgeagent.domain.model.LogProviderType;
import com.sitionix.forgeagent.domain.model.LogTargetCandidate;
import com.sitionix.forgeagent.domain.model.SshConnection;
import java.util.List;

public interface RemoteLogPort {
    List<LogTargetCandidate> discover(SshConnection connection, LogProviderType provider);
    void validate(SshConnection connection, LogProviderType provider, LogProviderConfiguration configuration);
    LogStream stream(SshConnection connection, LogProviderType provider, LogProviderConfiguration configuration, int initialLines);
}
