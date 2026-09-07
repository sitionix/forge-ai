package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class CodexEventPayloadSanitizer {
    static final int MAX_TEXT_BYTES = 65_536;
    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "password", "passwd", "secret", "token", "access_token",
            "refresh_token", "api_key", "apikey", "approvaltoken", "approval_token");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b[A-Z0-9_-]*(?:API[_-]?KEY|TOKEN|SECRET|PASSWORD|PASSWD|AUTHORIZATION)[A-Z0-9_-]*\\s*=\\s*)([^\\s]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    JsonNode sanitize(final JsonNode source) {
        return this.sanitize(source, null);
    }

    BoundedText boundedText(final String source) {
        final String sanitized = this.sanitizeText(source == null ? "" : source);
        final int originalBytes = sanitized.getBytes(StandardCharsets.UTF_8).length;
        if (originalBytes <= MAX_TEXT_BYTES) {
            return new BoundedText(sanitized, false, originalBytes, originalBytes);
        }
        int end = Math.min(sanitized.length(), MAX_TEXT_BYTES);
        while (end > 0 && sanitized.substring(0, end).getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            end--;
        }
        final String stored = sanitized.substring(0, end);
        return new BoundedText(stored, true, originalBytes, stored.getBytes(StandardCharsets.UTF_8).length);
    }

    String sanitizeText(final String value) {
        if (value == null) return null;
        String sanitized = PRIVATE_KEY.matcher(value).replaceAll(REDACTED);
        sanitized = BEARER.matcher(sanitized).replaceAll("Bearer " + REDACTED);
        return SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("$1" + REDACTED);
    }

    private JsonNode sanitize(final JsonNode source, final String fieldName) {
        if (source == null || source.isNull()) return JsonNodeFactory.instance.nullNode();
        if (fieldName != null && isSensitiveKey(fieldName)) {
            return JsonNodeFactory.instance.textNode(REDACTED);
        }
        if (source.isTextual()) return JsonNodeFactory.instance.textNode(this.sanitizeText(source.asText()));
        if (source.isArray()) {
            final ArrayNode result = JsonNodeFactory.instance.arrayNode();
            source.forEach(value -> result.add(this.sanitize(value, fieldName)));
            return result;
        }
        if (source.isObject()) {
            final ObjectNode result = JsonNodeFactory.instance.objectNode();
            final Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
            fields.forEachRemaining(entry -> result.set(entry.getKey(), this.sanitize(entry.getValue(), entry.getKey())));
            return result;
        }
        return source.deepCopy();
    }

    private static String normalize(final String key) {
        return key.toLowerCase().replace("-", "_");
    }

    private static boolean isSensitiveKey(final String fieldName) {
        final String key = normalize(fieldName);
        return SENSITIVE_KEYS.contains(key) || key.contains("token") || key.contains("secret")
                || key.contains("password") || key.contains("passwd") || key.contains("authorization")
                || key.equals("key") || key.endsWith("_key");
    }

    record BoundedText(String value, boolean truncated, int originalBytes, int storedBytes) { }
}
