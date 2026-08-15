package com.sitionix.forgeagent.infrastructure.postgres.repository;

import com.sitionix.forgeagent.infrastructure.postgres.entity.ExecutionFrameEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface SpringDataExecutionFrameRepository extends JpaRepository<ExecutionFrameEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from ExecutionFrameEntity f where f.id = :id")
    Optional<ExecutionFrameEntity> findByIdForUpdate(@Param("id") UUID id);
}
