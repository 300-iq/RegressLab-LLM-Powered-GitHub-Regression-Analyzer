package dev.regresslab.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessResourceException.class,
            CannotCreateTransactionException.class, TransactionSystemException.class})
    public ProblemDetail databaseUnavailable(RuntimeException exception) {
        log.error("Database operation failed", exception);
        return detail(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "The database is unavailable. Please retry later.");
    }

    @ExceptionHandler(ApiProblem.class)
    public ProblemDetail problem(ApiProblem problem) { return detail(problem.status(), problem.code(), problem.getMessage()); }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict(DataIntegrityViolationException exception) {
        return detail(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with stored data constraints.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            jakarta.validation.ConstraintViolationException.class})
    public ProblemDetail invalid(Exception exception) { return detail(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request body or parameters."); }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail missing(Exception exception) { return detail(HttpStatus.NOT_FOUND, "NOT_FOUND", "Endpoint was not found."); }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail media(Exception exception) { return detail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "JSON_REQUIRED", "Send application/json."); }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> method(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(exception.getHeaders())
                .body(detail(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method is not supported for this endpoint."));
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception exception) {
        log.error("Unexpected API failure", exception);
        return detail(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be completed.");
    }

    private static ProblemDetail detail(HttpStatus status, String code, String message) {
        var detail = ProblemDetail.forStatusAndDetail(status, message); detail.setProperty("code", code); return detail;
    }
}
