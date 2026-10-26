package ai.guitarmentor.infrastructure.security;

import java.text.Normalizer;
import java.util.Locale;

/** One policy for registration, linking, username changes and login lookup. */
final class CredentialPolicy {
    private CredentialPolicy() {}
    static String normalizeIdentifier(String value) {
        return Normalizer.normalize(Normalizer.normalize(value.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }
    static String login(String value) {
        String name = value == null ? "" : Normalizer.normalize(normalizeIdentifier(value), Normalizer.Form.NFC);
        int count = name.codePointCount(0, name.length());
        if (count < 3 || count > 64) throw new IllegalArgumentException("INVALID_INPUT");
        boolean afterLetter = false;
        int index = 0;
        for (int c : name.codePoints().toArray()) {
            boolean letter = Character.isLetter(c) &&
                (Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN ||
                 Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC);
            boolean digit = c >= '0' && c <= '9';
            int type = Character.getType(c);
            boolean mark = afterLetter && (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) &&
                (Character.UnicodeScript.of(c) == Character.UnicodeScript.INHERITED ||
                 Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN ||
                 Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC);
            if (!(letter || digit || (index > 0 && (mark || c == '.' || c == '_' || c == '-'))))
                throw new IllegalArgumentException("INVALID_INPUT");
            afterLetter = letter || mark;
            index++;
        }
        return name;
    }
    static void password(String value) {
        if (value == null) throw new IllegalArgumentException("INVALID_INPUT");
        int count = value.codePointCount(0, value.length());
        if (count < 6 || count > 128) throw new IllegalArgumentException("INVALID_INPUT");
    }
}
