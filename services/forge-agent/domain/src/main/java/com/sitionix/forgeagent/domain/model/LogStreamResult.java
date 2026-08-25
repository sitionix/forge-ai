package com.sitionix.forgeagent.domain.model;

public record LogStreamResult(int exitCode, String errorOutput) {
  public boolean successful() {
    return this.exitCode == 0;
  }
}
