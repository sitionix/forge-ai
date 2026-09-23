package com.sitionix.forgeagent.domain.model;
public record RemoteAccessPairingKeys(String publicKey, String fingerprint, RemoteAccessPrivateKey privateKey) implements AutoCloseable {
    @Override public void close() { privateKey.close(); }
    @Override public String toString() { return "RemoteAccessPairingKeys[REDACTED]"; }
}
