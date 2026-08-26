package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.model.SshAuthType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class RemoteShellCommand {
  private static final Pattern SSH_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,253}");

  private RemoteShellCommand() {}

  static List<String> ssh(final SshConnection connection, final List<String> remoteArguments) {
    if (connection == null) throw new ValidationException("SSH connection is required");
    if (!SSH_NAME.matcher(connection.username()).matches()
        || !SSH_NAME.matcher(connection.host()).matches()) {
      throw new ValidationException("SSH username or host is invalid");
    }
    final var command = new ArrayList<String>();
    if (connection.authType() == SshAuthType.PASSWORD) {
      command.addAll(
          List.of(
              "sshpass",
              "-e",
              "ssh",
              "-o",
              "PreferredAuthentications=password",
              "-o",
              "PubkeyAuthentication=no"));
    } else {
      command.addAll(List.of("ssh", "-o", "BatchMode=yes"));
    }
    command.addAll(
        List.of(
            "-o",
            "ConnectTimeout=10",
            "-p",
            String.valueOf(connection.port())));
    if (connection.authType() == SshAuthType.PRIVATE_KEY) {
      command.addAll(List.of("-i", connection.privateKeyPath()));
    }
    command.addAll(List.of("--", connection.username() + "@" + connection.host()));
    command.add(
        remoteArguments.stream()
            .map(RemoteShellCommand::quote)
            .collect(java.util.stream.Collectors.joining(" ")));
    return command;
  }

  static Map<String, String> environment(final SshConnection connection) {
    return connection != null && connection.authType() == SshAuthType.PASSWORD
        ? Map.of("SSHPASS", connection.password())
        : Map.of();
  }

  static boolean passwordAuthentication(final SshConnection connection) {
    return connection != null && connection.authType() == SshAuthType.PASSWORD;
  }

  static String quote(final String value) {
    if (value == null) throw new ValidationException("Remote command argument is required");
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }
}
