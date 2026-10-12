package com.sitionix.forgeagent.application.runtime;

import static org.junit.jupiter.api.Assertions.*;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.ExecutionDiagnosticText;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NodeRunFailureTest {
    @Test
    void reviewCredentialFormsAreRedactedWithoutRemovingSurroundingDiagnostics() {
        String message = "Rejected body: \"{\\\"token\\\":\\\"escaped-canary\\\"}\"; "
                + "headers={Authorization=[Custom custom-canary], requestId=turn-1}; "
                + "https://host/path?api%5Fkey=encoded-canary&safe=1";
        for (String input : new String[]{message, message.replace("\\", "\\\\").replace("\"", "\\\"")}) {
            for (String safe : new String[]{ExecutionDiagnosticText.sanitize(input), new NodeRunFailure("OTHER", input).message()}) {
                for (String secret : new String[]{"escaped-canary", "custom-canary", "encoded-canary"})
                    assertFalse(safe.contains(secret), secret);
                for (String expected : new String[]{"Rejected body", "requestId=turn-1", "https://host/path", "safe=1"})
                    assertTrue(safe.contains(expected), expected);
            }
        }
    }

    @Test
    void completeStructuredSecretValuesAndEncodedQueryKeysAreRedacted() {
        String message = "Tool rejected {\"token\":[\"first-canary\",{\"nested\":\"second-canary\"}],\"requestId\":\"turn-1\"}; "
                + "headers={Authorization={scheme=Custom, value=object-canary}, requestId=turn-1}; "
                + "https://host/path?%61%70%69%5F%6B%65%79=encoded-canary&key=query-canary&safe=1";
        String safe = ExecutionDiagnosticText.sanitize(message);
        for (String secret : new String[]{"first-canary", "second-canary", "object-canary", "encoded-canary", "query-canary"})
            assertFalse(safe.contains(secret), secret);
        assertTrue(safe.contains("Tool rejected"));
        assertTrue(safe.contains("requestId=turn-1"));
        assertTrue(safe.contains("safe=1"));
    }

    @Test
    void completeAuthorizationHeadersAndRecognizedProviderTokensRemainRedacted() {
        String pat = "github_pat_" + "p".repeat(40);
        String safe = ExecutionDiagnosticText.sanitize("Request rejected " + pat + "\n"
                + "Authorization: Digest username=fixture, nonce=digest-canary, response=response-canary\n"
                + "requestId=turn-1 https://host/path?X-Amz-Signature=signature-canary&safe=1");
        for (String secret : new String[]{pat, "digest-canary", "response-canary", "signature-canary"})
            assertFalse(safe.contains(secret), secret);
        assertTrue(safe.contains("Request rejected"));
        assertTrue(safe.contains("requestId=turn-1"));
        assertTrue(safe.contains("safe=1"));
    }

    @Test
    void opaqueHeaderValuesRemainFullyRedactedWhileQueryParametersKeepTheirBoundaries() {
        String safe = ExecutionDiagnosticText.sanitize("Request rejected\n"
                + "Authorization: Custom prefix{auth-canary}auth-tail-canary\n"
                + "Cookie: sid=prefix{cookie-canary}cookie-tail-canary; other=second-canary\n"
                + "requestId=turn-1 https://host/path?authorization=query-auth-canary&cookie=query-cookie-canary&safe=1");
        for (String secret : new String[]{"auth-canary", "auth-tail-canary", "cookie-canary", "cookie-tail-canary",
                "second-canary", "query-auth-canary", "query-cookie-canary"}) assertFalse(safe.contains(secret), secret);
        assertTrue(safe.contains("Request rejected"));
        assertTrue(safe.contains("requestId=turn-1"));
        assertTrue(safe.contains("safe=1"));
        assertEquals("Rejected {\"authorization\":[REDACTED],\"requestId\":\"turn-1\"}",
                ExecutionDiagnosticText.sanitize("Rejected {\"authorization\":null,\"requestId\":\"turn-1\"}"));
    }

    @Test
    void largeNonUrlTextDoesNotStallSynchronousDiagnosticHandling() {
        for (String character : new String[]{"x", "\\", "token"}) {
            String text = character.repeat(64_000 / character.length());
            assertTimeout(Duration.ofSeconds(2), () -> assertEquals(text, ExecutionDiagnosticText.sanitize(text)));
        }
    }

    @Test
    void terminalCodesHonorTheExisting120CharacterPersistenceLimit() {
        assertEquals("x".repeat(120), ExecutionDiagnosticText.code("x".repeat(120)));
        for (int length : new int[]{121, 128}) {
            String code = "x".repeat(length);
            assertNull(ExecutionDiagnosticText.code(code));
            assertEquals("AGENT_EXECUTOR_FAILED", new NodeRunFailure(code, "Request rejected").code());
        }
    }

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
