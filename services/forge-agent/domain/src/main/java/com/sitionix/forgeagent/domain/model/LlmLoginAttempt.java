package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

public record LlmLoginAttempt(UUID loginId, Status status, Instant expiresAt, String authUrl, String errorCode) {
    public enum Status { PENDING, COMPLETED, FAILED, CANCELLED, EXPIRED }

    public LlmLoginAttempt {
        if (status != Status.PENDING) authUrl = null;
    }

    @Override public String toString() {
        return "LlmLoginAttempt[loginId=" + loginId + ", status=" + status + ", expiresAt=" + expiresAt
                + ", errorCode=" + errorCode + "]";
    }
}
