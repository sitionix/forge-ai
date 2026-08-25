package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.SshConnectionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSshConnectionRepository extends JpaRepository<SshConnectionEntity, UUID> {
    List<SshConnectionEntity> findAllByProjectIdOrderByNameAscIdAsc(UUID projectId);
}
