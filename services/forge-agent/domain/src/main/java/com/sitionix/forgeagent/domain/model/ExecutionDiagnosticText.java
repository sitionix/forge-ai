package com.sitionix.forgeagent.domain.model;

import java.util.regex.Pattern;

/** Text redaction shared by provider activity and persisted execution failure summaries. */
public final class ExecutionDiagnosticText {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SENSITIVE_HEADER = Pattern.compile("(?i)(\\b(?:authorization|proxy-authorization|cookie|set-cookie)\\s*:\\s*)[^\\r\\n]+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+@");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?<![A-Z0-9_-])([\\\"']?[A-Z0-9_-]*(?:API[_-]?KEY|TOKEN|SECRET|PASSWORD|PASSWD|AUTHORIZATION|COOKIE|CREDENTIAL)[A-Z0-9_-]*[\\\"']?\\s*[:=]\\s*)"
                    + "(\\\"(?:\\\\.|[^\\\"\\\\])*(?:\\\"|$)|'(?:\\\\.|[^'\\\\])*(?:'|$)|[^\\s,;&}]+)");
    private static final Pattern KNOWN_TOKEN = Pattern.compile("\\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9_]{16,})");

    private ExecutionDiagnosticText() { }

    public static String sanitize(final String value) {
        if (value == null) return null;
        String safe = PRIVATE_KEY.matcher(value).replaceAll(REDACTED);
        safe = SENSITIVE_HEADER.matcher(safe).replaceAll("$1" + REDACTED);
        safe = AUTH_SCHEME.matcher(safe).replaceAll("$1 " + REDACTED);
        safe = URL_CREDENTIALS.matcher(safe).replaceAll("$1" + REDACTED + "@");
        safe = SECRET_ASSIGNMENT.matcher(safe).replaceAll("$1" + REDACTED);
        return KNOWN_TOKEN.matcher(safe).replaceAll(REDACTED);
    }

    public static String summary(final String value) {
        final String safe = sanitize(value);
        if (safe == null || safe.length() <= 2048) return safe;
        int end = 2047;
        if (Character.isHighSurrogate(safe.charAt(end - 1))) end--;
        return safe.substring(0, end) + "…";
    }

    public static String code(final String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,128}")
                && value.equals(sanitize(value)) ? value : null;
    }
}
