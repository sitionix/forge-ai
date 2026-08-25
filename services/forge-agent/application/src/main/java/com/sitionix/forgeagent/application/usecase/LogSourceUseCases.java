package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class LogSourceUseCases {
    private final ProjectRepository projects; private final LogSourceRepository sources;
    private final SshConnectionRepository connections; private final DockerLogPort docker; private final RemoteLogPort remote; private final Clock clock;
    @Transactional(readOnly=true) public List<LogSource> list(UUID projectId){project(projectId);return sources.findByProjectId(projectId);}
    public LogSource create(UUID projectId,SaveLogSourceCommand c){project(projectId);validate(projectId,c);var now=clock.instant();return sources.save(new LogSource(UUID.randomUUID(),projectId,c.name().strip(),c.serviceId(),c.connectionType(),c.sshConnectionId(),c.provider(),c.configuration(),c.enabled(),now,now));}
    public LogSource update(UUID projectId,UUID id,SaveLogSourceCommand c){var old=owned(projectId,id);validate(projectId,c);return sources.save(new LogSource(id,projectId,c.name().strip(),c.serviceId(),c.connectionType(),c.sshConnectionId(),c.provider(),c.configuration(),c.enabled(),old.createdAt(),clock.instant()));}
    public void delete(UUID projectId,UUID id){sources.delete(owned(projectId,id));}
    @Transactional(readOnly=true) public List<LogTargetCandidate> discover(UUID projectId,LogConnectionType connection,UUID sshId,LogProviderType provider){project(projectId);SshConnection ssh=resolve(projectId,connection,sshId);return provider==LogProviderType.DOCKER?docker.discover(ssh):remote.discover(ssh,provider);}
    @Transactional(readOnly=true) public void validateTarget(UUID projectId,SaveLogSourceCommand c){project(projectId);validate(projectId,c);SshConnection ssh=resolve(projectId,c.connectionType(),c.sshConnectionId());if(c.provider()==LogProviderType.DOCKER){var d=(DockerLogConfiguration)c.configuration();docker.validate(d.container(),d.composeService(),d.composeFile(),ssh);}else remote.validate(ssh,c.provider(),c.configuration());}
    @Transactional(readOnly=true) public LogStream stream(UUID projectId,UUID id,int lines){LogSource s=owned(projectId,id);if(!s.enabled())throw new ValidationException("Log source is disabled");SshConnection ssh=resolve(projectId,s.connectionType(),s.sshConnectionId());if(s.provider()==LogProviderType.DOCKER){var d=(DockerLogConfiguration)s.configuration();return docker.stream(d.container(),d.composeService(),d.composeFile(),lines,ssh);}return remote.stream(ssh,s.provider(),s.configuration(),lines);}
    private void validate(UUID projectId,SaveLogSourceCommand c){if(c.name()==null||c.name().isBlank())throw new ValidationException("Log source name is required");if(c.serviceId()!=null)throw new ValidationException("Service association is unavailable because this Forge version has no Service resource");if(c.connectionType()==LogConnectionType.LOCAL&&c.sshConnectionId()!=null)throw new ValidationException("Local sources cannot reference SSH");if(c.connectionType()==LogConnectionType.SSH)resolve(projectId,c.connectionType(),c.sshConnectionId());if(c.connectionType()==LogConnectionType.LOCAL&&c.provider()!=LogProviderType.DOCKER)throw new ValidationException("Only Docker supports a local connection");if(c.configuration()==null||!matches(c.provider(),c.configuration()))throw new ValidationException("Provider configuration does not match provider");}
    private boolean matches(LogProviderType p,LogProviderConfiguration c){return (p==LogProviderType.DOCKER&&c instanceof DockerLogConfiguration)||(p==LogProviderType.SYSTEMD&&c instanceof SystemdLogConfiguration)||(p==LogProviderType.FILE&&c instanceof FileLogConfiguration);}
    private Project project(UUID id){return projects.findById(id).orElseThrow(()->new NotFoundException("PROJECT_NOT_FOUND","Project not found"));}
    private LogSource owned(UUID p,UUID id){project(p);var s=sources.findById(id).orElseThrow(()->new NotFoundException("LOG_SOURCE_NOT_FOUND","Log source not found"));if(!s.projectId().equals(p))throw new NotFoundException("LOG_SOURCE_NOT_FOUND","Log source not found");return s;}
    private SshConnection resolve(UUID p,LogConnectionType type,UUID id){if(type==LogConnectionType.LOCAL)return null;if(id==null)throw new ValidationException("SSH connection is required");var c=connections.findById(id).orElseThrow(()->new NotFoundException("SSH_CONNECTION_NOT_FOUND","SSH connection not found"));if(!c.projectId().equals(p))throw new NotFoundException("SSH_CONNECTION_NOT_FOUND","SSH connection not found");return c;}
}
