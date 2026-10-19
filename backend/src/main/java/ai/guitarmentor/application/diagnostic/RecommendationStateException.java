package ai.guitarmentor.application.diagnostic;

/** Expected state conflict from a recommendation-only read. */
public final class RecommendationStateException extends RuntimeException {
    private final String code;
    public RecommendationStateException(String code) {
        super(code);
        this.code = code;
    }
    public String code() { return code; }
}
