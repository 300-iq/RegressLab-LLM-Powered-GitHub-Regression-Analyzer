package dev.regresslab.api;

import org.springframework.http.HttpStatus;

public class ApiProblem extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public ApiProblem(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static ApiProblem invalid(String message) { return new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ARTIFACT", message); }
    public static ApiProblem conflict(String message) { return new ApiProblem(HttpStatus.CONFLICT, "CONFLICT", message); }
    public static ApiProblem notFound(String message) { return new ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", message); }
}
