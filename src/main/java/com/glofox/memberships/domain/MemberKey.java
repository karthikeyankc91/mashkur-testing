package com.glofox.memberships.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * The normalised identity a member is matched on.
 *
 * <p>Normalisation happens in the compact constructor, which reassigns the
 * parameter. That means <em>every</em> construction normalises and an
 * unnormalised {@code MemberKey} cannot exist anywhere in the system. A static
 * factory would have left {@code new MemberKey(raw)} reachable.
 *
 * <p>Trimming uses neither {@code trim()} nor {@code strip()}, because both are
 * insufficient and in different ways. {@code trim()} only removes characters at
 * or below {@code U+0020}. {@code strip()} removes anything
 * {@link Character#isWhitespace(char)} accepts, which is broader but still
 * excludes non-breaking spaces by design — Java treats {@code U+00A0} as
 * <em>not</em> whitespace because it is not a line-break opportunity. A member
 * name pasted from a web page frequently carries a trailing {@code U+00A0}, and
 * under {@code strip()} that name would be a <strong>different member</strong>,
 * making the one-active-unlimited rule bypassable with an invisible character.
 * So {@link #isSpace(char)} accepts either predicate.
 *
 * <p>This closes leading and trailing invisible space only. Interior spacing,
 * zero-width characters and homoglyph confusables ({@code "Аlice"} with a
 * Cyrillic А) are deliberately out of scope; full confusable folding needs a
 * real member registry rather than a name string, and is recorded as a known
 * gap.
 *
 * <p>{@code toLowerCase(Locale.ROOT)} rather than {@code toLowerCase()}: under a
 * Turkish default locale {@code "ALICE".toLowerCase()} yields {@code "alıce"}
 * with a dotless i, which would make {@code "ALICE"} and {@code "alice"}
 * different members. The one-unlimited-membership rule would then be bypassable
 * by server configuration rather than by anything in the request.
 */
public record MemberKey(String value) {

    public MemberKey {
        Objects.requireNonNull(value, "member name");
        value = stripSpace(value).toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("member name must not be blank");
        }
    }

    private static String stripSpace(String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end && isSpace(raw.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(raw.charAt(end - 1))) {
            end--;
        }
        return raw.substring(start, end);
    }

    /**
     * True for both whitespace and Unicode space separators. The two predicates
     * disagree on non-breaking spaces, so both are needed: {@code isWhitespace}
     * rejects {@code U+00A0} and {@code isSpaceChar} rejects {@code \t} and
     * {@code \n}.
     */
    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
