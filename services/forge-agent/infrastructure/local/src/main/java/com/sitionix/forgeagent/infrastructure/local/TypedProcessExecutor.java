package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.model.SshConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class TypedProcessExecutor {
  List<String> output(List<String> command, Path cwd) {
    return output(command, cwd, null);
  }

  List<String> output(List<String> command, Path cwd, SshConnection ssh) {
    try {
      ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
      builder.environment().putAll(RemoteShellCommand.environment(ssh));
      if (cwd != null) builder.directory(cwd.toFile());
      Process process = builder.start();
      boolean done =
          process.waitFor(
              Duration.ofSeconds(15).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      if (!done) {
        process.destroyForcibly();
        throw failure("RUNTIME_TIMEOUT", "Runtime command timed out", null);
      }
      String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        if (ssh != null && process.exitValue() == 255) {
          throw failure(
              "SSH_TRANSPORT_FAILED", "SSH connection to the selected host failed", null);
        }
        throw failure("RUNTIME_COMMAND_FAILED", "Runtime command failed: " + text.strip(), null);
      }
      return text.lines().toList();
    } catch (IOException e) {
      if (RemoteShellCommand.passwordAuthentication(ssh)) {
        throw failure(
            "SSH_PASSWORD_AUTH_UNAVAILABLE",
            "Password SSH authentication requires sshpass",
            e);
      }
      if (ssh != null) {
        throw failure("SSH_TRANSPORT_FAILED", "SSH connection to the selected host failed", e);
      }
      throw failure("RUNTIME_UNAVAILABLE", "Runtime provider is unavailable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw failure("RUNTIME_INTERRUPTED", "Runtime command interrupted", e);
    }
  }

  ProcessLogStream stream(List<String> command, Path cwd) {
    return stream(command, cwd, null);
  }

  ProcessLogStream stream(List<String> command, Path cwd, SshConnection ssh) {
    try {
      ProcessBuilder b = new ProcessBuilder(command);
      b.environment().putAll(RemoteShellCommand.environment(ssh));
      if (cwd != null) b.directory(cwd.toFile());
      return new ProcessLogStream(b.start());
    } catch (IOException e) {
      if (RemoteShellCommand.passwordAuthentication(ssh)) {
        throw failure(
            "SSH_PASSWORD_AUTH_UNAVAILABLE",
            "Password SSH authentication requires sshpass",
            e);
      }
      throw failure("LOG_STREAM_START_FAILED", "Log stream could not be started", e);
    }
  }

  private InfrastructureExecutionException failure(String code, String message, Exception cause) {
    var failure = new InfrastructureExecutionException(code, message);
    if (cause != null) failure.initCause(cause);
    return failure;
  }
}
