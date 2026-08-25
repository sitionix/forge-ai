package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.port.SshConnectionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.SshConnectionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataSshConnectionRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class PostgresSshConnectionRepository implements SshConnectionRepository {
    private final SpringDataSshConnectionRepository repository;
    public List<SshConnection> findByProjectId(UUID id){return repository.findAllByProjectIdOrderByNameAscIdAsc(id).stream().map(this::domain).toList();}
    public Optional<SshConnection> findById(UUID id){return repository.findById(id).map(this::domain);}
    public SshConnection save(SshConnection c){return domain(repository.save(entity(c)));}
    public void delete(SshConnection c){repository.deleteById(c.id());}
    private SshConnection domain(SshConnectionEntity e){return new SshConnection(e.getId(),e.getProjectId(),e.getName(),e.getHost(),e.getPort(),e.getUsername(),e.getPrivateKeyPath(),e.getCreatedAt(),e.getUpdatedAt());}
    private SshConnectionEntity entity(SshConnection c){var e=new SshConnectionEntity();e.setId(c.id());e.setProjectId(c.projectId());e.setName(c.name());e.setHost(c.host());e.setPort(c.port());e.setUsername(c.username());e.setPrivateKeyPath(c.privateKeyPath());e.setCreatedAt(c.createdAt());e.setUpdatedAt(c.updatedAt());return e;}
}
