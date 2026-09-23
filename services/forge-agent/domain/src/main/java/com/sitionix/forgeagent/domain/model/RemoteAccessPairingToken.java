package com.sitionix.forgeagent.domain.model;
import java.util.Objects;
public record RemoteAccessPairingToken(String value) {
    public RemoteAccessPairingToken { Objects.requireNonNull(value); }
    @Override public String toString() { return "RemoteAccessPairingToken[REDACTED]"; }
}
