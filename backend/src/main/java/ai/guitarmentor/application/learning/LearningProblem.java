package ai.guitarmentor.application.learning;

import org.springframework.http.HttpStatus;

/** Safe external code only; never put audio paths or private feedback in an exception. */
public class LearningProblem extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public LearningProblem(HttpStatus status, String code) {
        super(code);
        this.status=status;
        this.code=code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
