package com.sitionix.forgeagent.application.dialogue;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DialogueCommandValidation {
    private final DialogueProperties properties;

    public String text(final String value) {
        if (value == null || value.codePointCount(0,value.length()) > properties.maxMessageCodePoints()
                || value.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)
                || value.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))) {
            throw new ValidationException("INVALID_DIALOGUE_MESSAGE", "A nonblank message within the dialogue text limit is required.");
        }
        return value;
    }

    public void command(final UUID requestId, final long expectedRevision) {
        if (requestId == null || expectedRevision < 0) {
            throw new ValidationException("INVALID_DIALOGUE_COMMAND", "A request ID and nonnegative revision are required.");
        }
    }

    public void turnBudget(final int turnCount) {
        if (turnCount >= properties.maxTurns()) {
            throw new ConflictException("DIALOGUE_TURN_LIMIT_EXCEEDED", "The dialogue turn limit has been reached.");
        }
    }

    public String fingerprint(final String kind, final long revision, final String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (kind + ":" + revision + ":" + (payload == null ? "N" : "S" + payload)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
