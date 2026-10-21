package com.sitionix.forgeagent.infrastructure.codex;

/** Native smoke fixtures pin the canonical skill-path contract, not inference/model support. */
final class CodexLibraryCapabilities {
 private CodexLibraryCapabilities() {}
 static void requireCanonicalSkillPaths(String version) {
  if (!"0.163.0".equals(version)) throw new UnverifiedProtocol();
 }
 static final class UnverifiedProtocol extends CodexTransportException {
  UnverifiedProtocol(){super("Runtime inventory path protocol is unverified");}
 }
}
