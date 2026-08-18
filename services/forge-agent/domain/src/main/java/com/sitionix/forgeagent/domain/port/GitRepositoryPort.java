package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.GitRemoteInspection;
import com.sitionix.forgeagent.domain.model.GitLocalRepositoryState;
import java.nio.file.Path;

public interface GitRepositoryPort {

    GitRemoteInspection inspectRemote(String remoteUrl);

    String resolveRepositoryName(String remoteUrl);

    GitLocalRepositoryState inspectLocalRepository(Path repositoryPath);

    void clone(String remoteUrl, Path targetPath);
}
