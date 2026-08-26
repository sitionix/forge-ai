package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.model.SshAuthType;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class SshConnectionUseCases {
  private static final Pattern SSH_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,253}");
  private final ProjectRepository projects;
  private final SshConnectionRepository connections;
  private final SshConnectionProbePort probe;
  private final Clock clock;

  @Transactional(readOnly = true)
  public List<SshConnection> list(UUID p) {
    project(p);
    return connections.findByProjectId(p).stream()
        .map(SshConnection::withoutSecretLocation)
        .toList();
  }

  public SshConnection create(UUID p, SaveSshConnectionCommand c) {
    project(p);
    check(c);
    var n = clock.instant();
    return connections
        .save(connection(p, c, n))
        .withoutSecretLocation();
  }

  public void test(UUID projectId, SaveSshConnectionCommand command) {
    project(projectId);
    check(command);
    var now = clock.instant();
    probe.test(connection(projectId, command, now));
  }

  private SshConnection connection(UUID projectId, SaveSshConnectionCommand command, java.time.Instant now) {
    return new SshConnection(
        UUID.randomUUID(), projectId, command.name().strip(), command.host().strip(), command.port(),
        command.username().strip(), command.authType(), command.privateKeyPath(), command.password(),
        now, now);
  }

  private void project(UUID p) {
    if (projects.findById(p).isEmpty())
      throw new NotFoundException("PROJECT_NOT_FOUND", "Project not found");
  }

  private void check(SaveSshConnectionCommand c) {
    if (c.name() == null
        || c.name().isBlank()
        || c.host() == null
        || !SSH_NAME.matcher(c.host()).matches()
        || c.username() == null
        || !SSH_NAME.matcher(c.username()).matches()
        || c.authType() == null)
      throw new ValidationException("SSH profile fields are required or invalid");
    if (c.port() < 1 || c.port() > 65535) throw new ValidationException("SSH port is invalid");
    if (c.authType() == SshAuthType.PRIVATE_KEY) {
      if (blank(c.privateKeyPath())
          || c.password() != null
          || c.privateKeyPath().indexOf('\n') >= 0
          || c.privateKeyPath().indexOf('\r') >= 0) {
        throw new ValidationException("Private-key SSH authentication is invalid");
      }
    } else if (blank(c.password()) || c.privateKeyPath() != null) {
      throw new ValidationException("Password SSH authentication is invalid");
    }
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
