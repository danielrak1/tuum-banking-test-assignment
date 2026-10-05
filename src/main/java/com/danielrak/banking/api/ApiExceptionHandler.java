package com.danielrak.banking.api;

import com.danielrak.banking.domain.AccountNotFoundException;
import com.danielrak.banking.domain.CurrencyNotOpenException;
import com.danielrak.banking.domain.InsufficientFundsException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps failures to RFC 9457 ProblemDetails with a {@code code}, per design.md §3. Every response,
 * including the ones Spring builds for protocol errors (404 route, 405, 415, …), passes through
 * {@link #createResponseEntity}, which adds a status-based code where none was set.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** One entry of {@code errors[]}. */
    record FieldProblem(String field, ErrorCode code, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new FieldProblem(
                        // No class-level constraints exist today; a global error still shows up, under the object name.
                        error instanceof FieldError fe ? fe.getField() : error.getObjectName(),
                        codeFor(error),
                        error.getDefaultMessage()))
                // Validator order is unspecified; sort so the highest-priority code comes first, stably.
                .sorted(Comparator.comparing(FieldProblem::code).thenComparing(FieldProblem::field))
                .toList();
        ErrorCode top = errors.getFirst().code();   // this exception always carries at least one error

        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Request validation failed", top);
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Malformed request body", ErrorCode.VALIDATION_FAILED);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * A path or query parameter that doesn't convert: 400. The UUID path ID gets the endpoint's
     * not-found code; any other parameter gets {@code VALIDATION_FAILED} (design.md §3). This is more
     * specific than the inherited {@code TypeMismatchException} handler, so Spring picks it.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Object> handleArgumentTypeMismatch(MethodArgumentTypeMismatchException ex, WebRequest request) {
        MethodParameter parameter = ex.getParameter();
        NotFoundCode notFound = isUuidPathVariable(parameter) ? parameter.getMethodAnnotation(NotFoundCode.class) : null;
        ErrorCode code = notFound != null ? notFound.value() : ErrorCode.VALIDATION_FAILED;
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Malformed '" + ex.getName() + "'", code);
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<Object> handleAccountNotFound(
            AccountNotFoundException ex, HandlerMethod handlerMethod, WebRequest request) {
        NotFoundCode notFound = handlerMethod.getMethodAnnotation(NotFoundCode.class);
        if (notFound == null) {
            // Don't throw here: Spring logs a failing @ExceptionHandler only at WARN, then falls back
            // to a code-less 500 that hides the real cause.
            log.error("{} threw AccountNotFoundException but has no @NotFoundCode", handlerMethod, ex);
            return internalError(ex, request);
        }
        ProblemDetail body = problem(HttpStatus.NOT_FOUND, ex.getMessage(), notFound.value());
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    /** A supported currency the account holds no balance in: the request is well formed, the account rejects it. */
    @ExceptionHandler(CurrencyNotOpenException.class)
    ResponseEntity<Object> handleCurrencyNotOpen(CurrencyNotOpenException ex, WebRequest request) {
        return unprocessable(ex, ErrorCode.INVALID_CURRENCY, request);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<Object> handleInsufficientFunds(InsufficientFundsException ex, WebRequest request) {
        return unprocessable(ex, ErrorCode.INSUFFICIENT_FUNDS, request);
    }

    /** Anything unexpected: the full exception goes to the ERROR log, the client gets a generic 500. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception for {}", request.getDescription(false), ex);
        return internalError(ex, request);
    }

    /** Adds a status-based protocol code to any ProblemDetail that has none (design.md §3). */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            problem.setProperty("code", protocolCode(statusCode).name());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    /** 422: well formed, but the account's state rejects it (design.md §3 rule 2). */
    private ResponseEntity<Object> unprocessable(RuntimeException ex, ErrorCode code, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage(), code);
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    private ResponseEntity<Object> internalError(Exception ex, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", ErrorCode.INTERNAL_ERROR);
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    private static boolean isUuidPathVariable(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(PathVariable.class) && parameter.getParameterType() == UUID.class;
    }

    private static ErrorCode protocolCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.BAD_REQUEST;
        };
    }

    /**
     * A field's code comes from its constraint (design.md §3); {@code getCode()} is the constraint's
     * simple name. Each field-specific code has its own constraint; any other gives {@code VALIDATION_FAILED}.
     */
    private static ErrorCode codeFor(ObjectError error) {
        return switch (error.getCode()) {
            case "SupportedCurrency" -> ErrorCode.INVALID_CURRENCY;
            case "SupportedDirection" -> ErrorCode.INVALID_DIRECTION;
            case "ValidAmount" -> ErrorCode.INVALID_AMOUNT;
            case "DescriptionPresent" -> ErrorCode.DESCRIPTION_MISSING;
            case null, default -> ErrorCode.VALIDATION_FAILED;
        };
    }

    private static ProblemDetail problem(HttpStatus status, String detail, ErrorCode code) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code.name());
        return body;
    }
}
