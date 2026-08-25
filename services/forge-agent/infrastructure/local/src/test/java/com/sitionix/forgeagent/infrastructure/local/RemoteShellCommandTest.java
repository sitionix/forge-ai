package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.*;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.SshConnection;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RemoteShellCommandTest {
  private final SshConnection ssh =
      new SshConnection(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "rover",
          "rover.local",
          22,
          "operator",
          "/keys/id_ed25519",
          Instant.EPOCH,
          Instant.EPOCH);

  @Test
  void quotesEveryRemoteArgumentAsOneShellCommand() {
    var command =
        RemoteShellCommand.ssh(
            ssh, List.of("tail", "--lines", "10", "--follow", "--", "/tmp/a; touch /tmp/x"));
    assertThat(command)
        .last()
        .isEqualTo("'tail' '--lines' '10' '--follow' '--' '/tmp/a; touch /tmp/x'");
    assertThat(command).doesNotContain("touch", "/tmp/x");
  }

  @Test
  void quotesCommandSubstitutionAndBackticksAsData() {
    assertThat(RemoteShellCommand.quote("$(touch /tmp/x)`id`|'x'"))
        .isEqualTo("'$(touch /tmp/x)`id`|'\"'\"'x'\"'\"''");
  }

  @Test
  void rejectsInjectedSshHostAndUsername() {
    var badHost =
        new SshConnection(
            ssh.id(),
            ssh.projectId(),
            ssh.name(),
            "host;touch",
            22,
            ssh.username(),
            ssh.privateKeyPath(),
            ssh.createdAt(),
            ssh.updatedAt());
    assertThatThrownBy(() -> RemoteShellCommand.ssh(badHost, List.of("true")))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void constrainedRuntimeIdentifiersRejectShellMetacharacters() {
    for (String value : List.of("x; touch /tmp/x", "$(id)", "`id`", "x && id", "x|id"))
      assertThatThrownBy(() -> RuntimeTargetValidator.docker(value, "Container"))
          .isInstanceOf(ValidationException.class);
  }
}
