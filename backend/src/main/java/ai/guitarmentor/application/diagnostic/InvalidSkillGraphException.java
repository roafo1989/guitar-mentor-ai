package ai.guitarmentor.application.diagnostic;

/** Internal integrity violation; never exposed as a user transition conflict. */
public final class InvalidSkillGraphException extends IllegalStateException {
    public InvalidSkillGraphException(String message) { super(message); }
}
