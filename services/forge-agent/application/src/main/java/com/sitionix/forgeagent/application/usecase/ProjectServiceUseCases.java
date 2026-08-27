package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class ProjectServiceUseCases {
  private static final Pattern CONTAINER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");
  private static final Pattern UNIT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:_.@\\-]{0,253}\\.[A-Za-z0-9_.@-]+");
  private final ProjectRepository projects;
  private final ProjectServiceRepository services;
  private final ProjectRepositoryLinkRepository repositories;
  private final SshConnectionRepository connections;
  private final ServiceRuntimeInspectionPort runtime;
  private final Clock clock;

  @Transactional(readOnly=true) public List<ProjectService> list(UUID projectId) { project(projectId); return services.findByProjectId(projectId); }
  @Transactional(readOnly=true) public ProjectService get(UUID projectId, UUID id) { return owned(projectId,id); }
  public ProjectService create(UUID projectId, SaveProjectServiceCommand c) {
    project(projectId); validate(projectId,c); var now=clock.instant();
    return services.save(new ProjectService(UUID.randomUUID(),projectId,c.name().strip(),c.repositoryId(),c.runtimeTarget(),now,now));
  }
  public ProjectService update(UUID projectId, UUID id, SaveProjectServiceCommand c) {
    var old=owned(projectId,id); validate(projectId,c);
    return services.save(new ProjectService(id,projectId,c.name().strip(),c.repositoryId(),c.runtimeTarget(),old.createdAt(),clock.instant()));
  }
  public void delete(UUID projectId, UUID id) { services.delete(owned(projectId,id)); }
  @Transactional(readOnly=true) public ServiceRuntimeView runtime(UUID projectId, UUID id) {
    var service=owned(projectId,id); var target=service.runtimeTarget();
    return runtime.inspect(service, target.connection()==ServiceConnectionType.SSH ? ssh(projectId,target.sshConnectionId()) : null);
  }
  private void validate(UUID projectId, SaveProjectServiceCommand c) {
    if(c==null||c.name()==null||c.name().isBlank()) throw new ValidationException("Service name is required");
    if(c.repositoryId()!=null) { var r=repositories.findById(c.repositoryId()).orElseThrow(()->new NotFoundException("PROJECT_REPOSITORY_NOT_FOUND","Project repository not found")); if(!r.projectId().equals(projectId)) throw new NotFoundException("PROJECT_REPOSITORY_NOT_FOUND","Project repository not found"); }
    var t=c.runtimeTarget(); if(t==null||t.connection()==null||t.provider()==null) throw new ValidationException("Runtime connection and provider are required");
    if(t.connection()==ServiceConnectionType.LOCAL&&t.sshConnectionId()!=null) throw new ValidationException("Local runtime cannot reference SSH");
    if(t.connection()==ServiceConnectionType.SSH) ssh(projectId,t.sshConnectionId());
    if(t.provider()==ServiceRuntimeProvider.DOCKER) { if(t.container()==null||!CONTAINER.matcher(t.container()).matches()||t.unit()!=null) throw new ValidationException("Docker container is required or invalid"); }
    else if(t.unit()==null||!UNIT.matcher(t.unit()).matches()||t.container()!=null) throw new ValidationException("Systemd unit is required or invalid");
  }
  private void project(UUID id){ if(projects.findById(id).isEmpty()) throw new NotFoundException("PROJECT_NOT_FOUND","Project not found"); }
  private ProjectService owned(UUID p,UUID id){ project(p); var s=services.findById(id).orElseThrow(()->new NotFoundException("SERVICE_NOT_FOUND","Service not found")); if(!s.projectId().equals(p)) throw new NotFoundException("SERVICE_NOT_FOUND","Service not found"); return s; }
  private SshConnection ssh(UUID p,UUID id){ if(id==null) throw new ValidationException("SSH connection is required"); var s=connections.findById(id).orElseThrow(()->new NotFoundException("SSH_CONNECTION_NOT_FOUND","SSH connection not found")); if(!s.projectId().equals(p)) throw new NotFoundException("SSH_CONNECTION_NOT_FOUND","SSH connection not found"); return s; }
}
