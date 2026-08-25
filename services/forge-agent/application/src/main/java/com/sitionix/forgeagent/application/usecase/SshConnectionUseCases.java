package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;import java.util.*;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class SshConnectionUseCases {
 private final ProjectRepository projects;private final SshConnectionRepository connections;private final Clock clock;
 @Transactional(readOnly=true) public List<SshConnection> list(UUID p){project(p);return connections.findByProjectId(p).stream().map(SshConnection::withoutSecretLocation).toList();}
 public SshConnection create(UUID p,SaveSshConnectionCommand c){project(p);check(c);var n=clock.instant();return connections.save(new SshConnection(UUID.randomUUID(),p,c.name().strip(),c.host().strip(),c.port(),c.username().strip(),c.privateKeyPath(),n,n)).withoutSecretLocation();}
 private void project(UUID p){if(projects.findById(p).isEmpty())throw new NotFoundException("PROJECT_NOT_FOUND","Project not found");}
 private void check(SaveSshConnectionCommand c){if(c.name()==null||c.name().isBlank()||c.host()==null||c.host().isBlank()||c.username()==null||c.username().isBlank()||c.privateKeyPath()==null||c.privateKeyPath().isBlank())throw new ValidationException("SSH profile fields are required");if(c.port()<1||c.port()>65535)throw new ValidationException("SSH port is invalid");}
}
