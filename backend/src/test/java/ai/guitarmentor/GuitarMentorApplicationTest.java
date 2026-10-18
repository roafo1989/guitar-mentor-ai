package ai.guitarmentor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class GuitarMentorApplicationTest {

    @Test
    void applicationClassCanBeInstantiated() {
        assertDoesNotThrow(GuitarMentorApplication::new);
    }
}
