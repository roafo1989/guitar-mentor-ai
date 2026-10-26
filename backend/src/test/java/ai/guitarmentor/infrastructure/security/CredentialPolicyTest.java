package ai.guitarmentor.infrastructure.security;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class CredentialPolicyTest {
    @Test void latinCyrillicCaseAndCanonicalForms() {
        assertEquals("роман-1989", CredentialPolicy.login("  РОМАН-1989  "));
        assertEquals("roman_я.ё", CredentialPolicy.login("Roman_Я.Ё"));
        assertEquals(CredentialPolicy.login("ЙоЖ"), CredentialPolicy.login("И\u0306оЖ"));
        assertEquals("élève", CredentialPolicy.login("ÉLÈVE"));
        assertEquals("юлія", CredentialPolicy.login("ЮЛІЯ"));
        assertEquals(CredentialPolicy.login("İlyas"), CredentialPolicy.normalizeIdentifier("İLYAS"));
        assertEquals(CredentialPolicy.login("J\u030CONA"), CredentialPolicy.normalizeIdentifier("J\u030CONA"));
        assertEquals("a".repeat(64), CredentialPolicy.login("A".repeat(64)));
    }
    @Test void malformedLoginsAndLengthsAreRejected() {
        for (String bad : new String[]{"", "ab", "x".repeat(65), "a b", "_abc", ".abc", "a@b", "аб😀", "用户甲", "ab\u200Dcd", "a\nb", "a\u0000b", "ab\u0301.\u0301"})
            assertThrows(IllegalArgumentException.class, () -> CredentialPolicy.login(bad), bad);
        assertThrows(IllegalArgumentException.class, () -> CredentialPolicy.login(null));
    }
    @Test void passwordBoundariesUseCharactersAndKeepMaximum() {
        for (String accepted : new String[]{"123456", "x".repeat(128), "😀".repeat(6)})
            assertDoesNotThrow(() -> CredentialPolicy.password(accepted));
        for (String rejected : new String[]{"12345", "x".repeat(129), "😀".repeat(5)})
            assertThrows(IllegalArgumentException.class, () -> CredentialPolicy.password(rejected));
        assertThrows(IllegalArgumentException.class, () -> CredentialPolicy.password(null));
    }
}
