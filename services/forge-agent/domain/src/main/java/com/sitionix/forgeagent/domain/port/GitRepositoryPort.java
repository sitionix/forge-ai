package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.GitRemoteInspection;
import java.nio.file.Path;

public interface GitRepositoryPort {

    GitRemoteInspection inspectRemote(String remoteUrl);

    String resolveRepositoryName(String remoteUrl);

    void clone(String remoteUrl, Path targetPath);
}
