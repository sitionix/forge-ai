package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.LogSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LogSourceRepository {
    List<LogSource> findByProjectId(UUID projectId);
    List<LogSource> findByProjectIdAndServiceId(UUID projectId, UUID serviceId);
    Optional<LogSource> findById(UUID id);
    LogSource save(LogSource source);
    void delete(LogSource source);
}
