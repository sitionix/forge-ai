package com.sitionix.forgeagent.application.runtime;

import static org.junit.jupiter.api.Assertions.*;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import org.junit.jupiter.api.Test;

class NodeRunFailureTest {
    @Test
    void terminalSummaryPreservesTheReasonButRedactsCredentialForms() {
        var failure = new NodeRunFailure("unauthorized", "Sign in again. "
                + "Authorization: Bearer bearer-secret\nAuthorization: Basic basic-secret\n"
                + "{\"access_token\":\"json-secret\",\"password\":\"two word secret\"} "
                + "https://user:password@host/path?client_secret=query-secret&safe=1 "
                + "API_KEY=assignment-secret client_secret: colon-secret "
                + "-----BEGIN PRIVATE KEY-----private-key-secret-----END PRIVATE KEY-----");
        assertEquals("unauthorized", failure.code());
        for (String expected : new String[]{"Sign in again", "[REDACTED]", "safe=1"})
            assertTrue(failure.message().contains(expected), expected);
        for (String secret : new String[]{"bearer-secret", "basic-secret", "json-secret", "two word secret",
                "user:password", "query-secret", "assignment-secret", "colon-secret", "private-key-secret"})
            assertFalse(failure.message().contains(secret), secret);
    }

    @Test
    void terminalSummaryIsConciseAndDoesNotCutOffBeforeRedacting() {
        var failure = new NodeRunFailure("OTHER", "x".repeat(2040) + " TOKEN=" + "s".repeat(9000));
        assertTrue(failure.message().length() <= 2048);
        assertTrue(failure.message().endsWith("…"));
        assertFalse(failure.message().contains("ss"));
    }

    @Test
    void escapedSecretsCustomAuthorizationAndIncompletePrivateKeysAreRedacted() {
        var failure = new NodeRunFailure("OTHER", "Rejected {\"token\":\"prefix\\\"secret-suffix\"}\n"
                + "Authorization: Custom custom-secret\nCookie: first=cookie-one; second=cookie-two\n"
                + "wss://username:url-secret@host\n-----BEGIN PRIVATE KEY-----incomplete-key");
        for (String secret : new String[]{"prefix", "secret-suffix", "custom-secret", "cookie-one", "cookie-two",
                "url-secret", "incomplete-key"}) assertFalse(failure.message().contains(secret), secret);
        assertTrue(failure.message().startsWith("Rejected"));
    }
}
