package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.SshConnection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SshConnectionRepository {
    List<SshConnection> findByProjectId(UUID projectId);
    Optional<SshConnection> findById(UUID id);
    SshConnection save(SshConnection connection);
    void delete(SshConnection connection);
}
