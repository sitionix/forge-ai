package com.sitionix.forgeagent.domain.model;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text redaction shared by provider activity and persisted execution failure summaries. */
public final class ExecutionDiagnosticText {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]*+://)[^\\s/@]++@");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?<![A-Z0-9_\\\\-])(?:\\\\*+[\\\"'])?([A-Z0-9_-]++)(?:\\\\*+[\\\"'])?\\s*[:=]\\s*");
    private static final Pattern QUERY_ASSIGNMENT = Pattern.compile("([?&])([^\\s=?&#]+)=([^\\s&#]*)");
    private static final Pattern KNOWN_TOKEN = Pattern.compile("\\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9_]{16,}|github_pat_[A-Za-z0-9_]{16,})");

    private ExecutionDiagnosticText() { }

    public static String sanitize(final String value) {
        if (value == null) return null;
        String safe = PRIVATE_KEY.matcher(value).replaceAll(REDACTED);
        safe = URL_CREDENTIALS.matcher(safe).replaceAll("$1" + REDACTED + "@");
        safe = redactAssignments(safe);
        safe = redactQuery(safe);
        safe = AUTH_SCHEME.matcher(safe).replaceAll("$1 " + REDACTED);
        return KNOWN_TOKEN.matcher(safe).replaceAll(REDACTED);
    }

    private static String redactAssignments(final String value) {
        final Matcher matcher = SECRET_ASSIGNMENT.matcher(value);
        final StringBuilder safe = new StringBuilder(value.length());
        int cursor = 0;
        int search = 0;
        while (matcher.find(search)) {
            search = matcher.end();
            final String key = decodedQueryKey(matcher.group(1));
            if (!sensitiveKey(key)) continue;
            safe.append(value, cursor, matcher.end()).append(REDACTED);
            final boolean query = matcher.start() > 0 && "?&".indexOf(value.charAt(matcher.start() - 1)) >= 0;
            final boolean quotedKey = matcher.group().indexOf('"') >= 0 || matcher.group().indexOf('\'') >= 0;
            cursor = secretValueEnd(value, matcher.end(), !query && !quotedKey && (key.contains("authorization") || key.contains("cookie")));
            search = cursor;
        }
        return safe.append(value, cursor, value.length()).toString();
    }

    private static int secretValueEnd(final String value, final int start, final boolean header) {
        final int quotedEnd = quotedValueEnd(value, start);
        if (quotedEnd >= 0) return quotedEnd;
        if (start < value.length() && (value.charAt(start) == '[' || value.charAt(start) == '{')) {
            int depth = 0;
            int i = start;
            while (i < value.length()) {
                final char c = value.charAt(i);
                if (c == '\\' || c == '"' || c == '\'') {
                    final int end = quotedValueEnd(value, i);
                    if (end >= 0) { i = end; continue; }
                    while (i < value.length() && value.charAt(i) == '\\') i++;
                    continue;
                }
                if (c == '[' || c == '{') depth++;
                if ((c == ']' || c == '}') && --depth == 0) return i + 1;
                i++;
            }
            return value.length();
        }
        int end = start;
        while (end < value.length()) {
            final char c = value.charAt(end);
            if (c == '\r' || c == '\n'
                    || !header && (c == '&' || c == '}' || c == ']' || c == ',' || c == ';' || Character.isWhitespace(c))) break;
            end++;
        }
        return end;
    }

    private static int quotedValueEnd(final String value, final int start) {
        int quoteIndex = start;
        while (quoteIndex < value.length() && value.charAt(quoteIndex) == '\\') quoteIndex++;
        if (quoteIndex == value.length() || value.charAt(quoteIndex) != '"' && value.charAt(quoteIndex) != '\'') return -1;
        final char quote = value.charAt(quoteIndex);
        final int openingSlashes = quoteIndex - start;
        // A JSON string escaped into another string has 1, 3, 7, ... slashes
        // before delimiters; embedded quotes have a different escape parity.
        final long period = 2L * (openingSlashes + 1);
        int slashes = 0;
        for (int i = quoteIndex + 1; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\\') slashes++;
            else {
                if (c == quote && slashes % period == openingSlashes) return i + 1;
                slashes = 0;
            }
        }
        return value.length();
    }

    private static String redactQuery(final String value) {
        final Matcher matcher = QUERY_ASSIGNMENT.matcher(value);
        final StringBuilder safe = new StringBuilder(value.length());
        while (matcher.find()) {
            final String key = decodedQueryKey(matcher.group(2));
            if (sensitiveKey(key) || key.equals("key") || key.contains("signature")) {
                matcher.appendReplacement(safe, Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + "=" + REDACTED));
            }
        }
        return matcher.appendTail(safe).toString();
    }

    private static boolean sensitiveKey(final String key) {
        return key.contains("apikey") || key.contains("token") || key.contains("secret")
                || key.contains("password") || key.contains("passwd") || key.contains("authorization")
                || key.contains("cookie") || key.contains("credential");
    }

    private static String decodedQueryKey(final String value) {
        final StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                final int high = Character.digit(value.charAt(i + 1), 16);
                final int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) { c = (char) (high * 16 + low); i += 2; }
            }
            if (c != '_' && c != '-') decoded.append(Character.toLowerCase(c));
        }
        return decoded.toString();
    }

    public static String summary(final String value) {
        final String safe = sanitize(value);
        if (safe == null || safe.length() <= 2048) return safe;
        int end = 2047;
        if (Character.isHighSurrogate(safe.charAt(end - 1))) end--;
        return safe.substring(0, end) + "…";
    }

    public static String code(final String value) {
        return value != null && value.length() <= 120 ? diagnosticCode(value) : null;
    }

    public static String diagnosticCode(final String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,128}")
                && value.equals(sanitize(value)) ? value : null;
    }
}
