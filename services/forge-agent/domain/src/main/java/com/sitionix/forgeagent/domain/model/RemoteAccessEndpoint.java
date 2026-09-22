package com.sitionix.forgeagent.domain.model;

public record RemoteAccessEndpoint(String host, int port, String username) {
    public RemoteAccessEndpoint {
        requireText(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        requireText(username, "username");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
