package com.sitionix.forgeagent.infrastructure.postgres.adapter;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.ProjectServiceRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.ProjectServiceEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataProjectServiceRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
@Repository @RequiredArgsConstructor
public class PostgresProjectServiceRepository implements ProjectServiceRepository {
 private final SpringDataProjectServiceRepository repository;
 public List<ProjectService> findByProjectId(UUID id){return repository.findAllByProjectIdOrderByNameAscIdAsc(id).stream().map(this::domain).toList();}
 public Optional<ProjectService> findById(UUID id){return repository.findById(id).map(this::domain);}
 public ProjectService save(ProjectService s){return domain(repository.save(entity(s)));}
 public void delete(ProjectService s){repository.deleteById(s.id());}
 private ProjectService domain(ProjectServiceEntity e){return new ProjectService(e.getId(),e.getProjectId(),e.getName(),e.getRepositoryId(),new ServiceRuntimeTarget(e.getConnectionType(),e.getSshConnectionId(),e.getProvider(),e.getDockerContainer(),e.getSystemdUnit()),e.getCreatedAt(),e.getUpdatedAt());}
 private ProjectServiceEntity entity(ProjectService s){var e=new ProjectServiceEntity(); var t=s.runtimeTarget(); e.setId(s.id());e.setProjectId(s.projectId());e.setName(s.name());e.setRepositoryId(s.repositoryId());e.setConnectionType(t.connection());e.setSshConnectionId(t.sshConnectionId());e.setProvider(t.provider());e.setDockerContainer(t.container());e.setSystemdUnit(t.unit());e.setCreatedAt(s.createdAt());e.setUpdatedAt(s.updatedAt());return e;}
}
