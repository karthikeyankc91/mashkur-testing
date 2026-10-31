package com.glofox.memberships.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Each case here defends one decision in {@link MemberKey}. Plain JUnit with no
 * Spring context, which is also the layering boundary demonstrated in practice:
 * the domain needs no framework to be testable.
 */
class MemberKeyTest {

    @Test
    @DisplayName("case and surrounding whitespace do not make a different member")
    void equivalentNames() {
        MemberKey canonical = new MemberKey("alice");

        assertThat(new MemberKey("Alice")).isEqualTo(canonical);
        assertThat(new MemberKey("ALICE")).isEqualTo(canonical);
        assertThat(new MemberKey("  Alice  ")).isEqualTo(canonical);
        assertThat(new MemberKey("aLiCe")).isEqualTo(canonical);
    }

    @Test
    @DisplayName("normalisation happens on construction, so the value is already folded")
    void valueIsNormalised() {
        assertThat(new MemberKey("  Alice  ").value()).isEqualTo("alice");
    }

    @Test
    @DisplayName("invisible unicode space does not create a second member")
    void unicodeSpaceStripped() {
        // Both are above U+0020, so trim() leaves them in place. U+2003 is
        // Character.isWhitespace, so strip() would catch it; U+00A0 is NOT
        // (Java excludes non-breaking spaces), so strip() alone would let
        // "Alice " become a distinct member and hold a second unlimited
        // membership. This is the case that drove isWhitespace || isSpaceChar.
        String noBreakSpace = " ";
        String emSpace = " ";

        assertThat(new MemberKey(noBreakSpace + "Alice" + noBreakSpace))
                .isEqualTo(new MemberKey("alice"));
        assertThat(new MemberKey(emSpace + "Alice" + emSpace))
                .isEqualTo(new MemberKey("alice"));
        assertThat(new MemberKey("Alice" + noBreakSpace + emSpace + "\t"))
                .isEqualTo(new MemberKey("alice"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " ", " "})
    @DisplayName("a name that is blank once normalised is rejected")
    void blankRejected(String blank) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new MemberKey(blank))
                .withMessageContaining("blank");
    }

    @Test
    @DisplayName("null is rejected distinctly from blank")
    void nullRejected() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new MemberKey(null));
    }
}
