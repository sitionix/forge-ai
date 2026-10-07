package com.sitionix.forgeagent.application.dialogue;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DialogueCommandValidationTest {
    private final DialogueCommandValidation validation = new DialogueCommandValidation(new DialogueProperties(100,16000,128000));

    @Test void whitespaceOnlyIsRejectedIncludingUnicode() {
        for (String text : new String[]{"", " \n\t", "\u2003\u00a0", "\u202f"}) {
            assertThatThrownBy(() -> validation.text(text)).isInstanceOf(ValidationException.class);
        }
    }
    @Test void unicodeLimitCountsCodePointsAndPreservesNewlines() {
        String text = "😀".repeat(16000);
        assertThat(validation.text(text)).isEqualTo(text);
        assertThatThrownBy(() -> validation.text(text + "😀")).isInstanceOf(ValidationException.class);
        assertThat(validation.text("  first\nsecond  ")).isEqualTo("  first\nsecond  ");
    }
    @Test void invalidUnicodeIsRejectedAndNullPayloadHasDistinctFingerprint() {
        assertThatThrownBy(() -> validation.text("\uD800")).isInstanceOf(ValidationException.class);
        assertThat(validation.fingerprint("CHAT",1,null)).isNotEqualTo(validation.fingerprint("CHAT",1,"null"));
    }
    @Test void turnLimitIncludesInitialAndSummary() {
        validation.turnBudget(99);
        assertThatThrownBy(() -> validation.turnBudget(100)).isInstanceOf(ConflictException.class);
    }
    @Test void fingerprintsPreservePayloadAndRevision() {
        assertThat(validation.fingerprint("CHAT", 2, "a\nb"))
                .isEqualTo(validation.fingerprint("CHAT",2,"a\nb"))
                .isNotEqualTo(validation.fingerprint("CHAT",2,"ab"))
                .isNotEqualTo(validation.fingerprint("SUMMARY",2,"a\nb"))
                .isNotEqualTo(validation.fingerprint("CHAT",3,"a\nb"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {8000, 20000})
    void configuredMessageLimitCountsUnicodeCodePoints(final int limit) {
        var configured = new DialogueCommandValidation(new DialogueProperties(100,limit,128000));
        String text = "😀".repeat(limit);
        assertThat(configured.text(text)).isEqualTo(text);
        assertThatThrownBy(() -> configured.text(text + "😀")).isInstanceOf(ValidationException.class);
    }
}
