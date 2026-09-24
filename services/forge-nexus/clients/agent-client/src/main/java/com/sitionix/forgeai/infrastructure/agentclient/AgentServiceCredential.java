package com.sitionix.forgeai.infrastructure.agentclient;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

/** Credential restricted to the configured, fixed Agent origin. */
final class AgentServiceCredential {
  private final URI origin;
  private final String authorization;

  AgentServiceCredential(final Path file, final URI origin) {
    if (origin == null || !origin.isAbsolute() || origin.getHost() == null
        || origin.getRawUserInfo() != null || origin.getRawQuery() != null
        || origin.getRawFragment() != null
        || (origin.getRawPath() != null && !origin.getRawPath().isEmpty()
            && !origin.getRawPath().equals("/"))) {
      throw new IllegalArgumentException("Invalid Agent origin");
    }
    final boolean secure = "https".equalsIgnoreCase(origin.getScheme());
    final boolean loopback = "http".equalsIgnoreCase(origin.getScheme())
        && ("localhost".equalsIgnoreCase(origin.getHost())
            || "127.0.0.1".equals(origin.getHost())
            || "[::1]".equals(origin.getHost())
            || "::1".equals(origin.getHost()));
    if (!secure && !loopback) {
      throw new IllegalArgumentException("Invalid Agent origin");
    }
    this.origin = origin;
    final byte[] secret = ProtectedNexusFile.token(file);
    try {
      this.authorization = "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  String authorization() {
    return this.authorization;
  }

  URI origin() {
    return this.origin;
  }

  @Override
  public String toString() {
    return "AgentServiceCredential[redacted]";
  }
}
