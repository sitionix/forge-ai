package com.sitionix.forgeagent.domain.model;

import java.util.Arrays;
import java.util.Objects;

public final class RemoteAccessPrivateKey implements AutoCloseable {

    private final byte[] bytes;
    private boolean closed;

    public RemoteAccessPrivateKey(final byte[] bytes) {
        Objects.requireNonNull(bytes, "Private key bytes are required.");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Private key bytes must not be empty.");
        }
        this.bytes = Arrays.copyOf(bytes, bytes.length);
    }

    public synchronized byte[] copyBytes() {
        if (this.closed) {
            throw new IllegalStateException("Private key material has been destroyed.");
        }
        return Arrays.copyOf(this.bytes, this.bytes.length);
    }

    @Override
    public synchronized void close() {
        if (!this.closed) {
            Arrays.fill(this.bytes, (byte) 0);
            this.closed = true;
        }
    }

    @Override
    public String toString() {
        return "RemoteAccessPrivateKey[REDACTED]";
    }
}
