package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.LogTargetCandidate;
import com.sitionix.forgeagent.domain.model.SshConnection;
import java.nio.file.Path;
import java.util.List;

public interface DockerLogPort {
    List<LogTargetCandidate> discoverComposeServices(Path repository, SshConnection connection);
    void validate(String container, String composeService, String composeFile, SshConnection connection);
    LogStream stream(String container, String composeService, String composeFile, int initialLines, SshConnection connection);
}
