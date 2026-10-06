package com.danielrak.banking.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.danielrak.banking.domain.AccountNotFoundException;
import jakarta.validation.Validation;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * White-box tests for the {@link ApiExceptionHandler} paths that no request to today's endpoints can
 * reach. Everything reachable over HTTP is covered black-box by the {@code *IT} classes.
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(Validation.buildDefaultValidatorFactory().getValidator());
    private final ServletWebRequest request =
            new ServletWebRequest(new MockHttpServletRequest("GET", "/accounts/x"), new MockHttpServletResponse());

    /** Stand-in handlers: one annotated like a real endpoint, one missing the annotation. */
    static class Fixture {
        @NotFoundCode(ErrorCode.INVALID_ACCOUNT)
        void annotated(@PathVariable UUID accountId, @RequestParam Integer limit) {
        }

        void unannotated(@PathVariable UUID accountId) {
        }
    }

    @Test
    void unexpectedExceptionGivesGeneric500AndLogsTheCause(CapturedOutput output) {
        ResponseEntity<Object> response =
                handler.handleUnexpected(new IllegalStateException("secret: db password is hunter2"), request);

        ProblemDetail problem = problem(response, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(problem.getDetail()).doesNotContain("secret");
        assertThat(output).contains("ERROR").contains("secret: db password is hunter2");
    }

    @Test
    void accountNotFoundOnHandlerWithoutNotFoundCodeGives500AndLogsAtError(CapturedOutput output) throws Exception {
        HandlerMethod unannotated = new HandlerMethod(new Fixture(), Fixture.class.getDeclaredMethod("unannotated", UUID.class));
        UUID id = UUID.randomUUID();

        ResponseEntity<Object> response = handler.handleAccountNotFound(new AccountNotFoundException(id), unannotated, request);

        ProblemDetail problem = problem(response, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(problem.getDetail()).doesNotContain(id.toString());
        assertThat(output).contains("ERROR").contains("has no @NotFoundCode");
    }

    @Test
    void typeMismatchOnPathIdGetsTheEndpointCode() throws Exception {
        ResponseEntity<Object> response = handler.handleArgumentTypeMismatch(mismatch(0, UUID.class), request);

        assertThat(problem(response, HttpStatus.BAD_REQUEST).getProperties()).containsEntry("code", "INVALID_ACCOUNT");
    }

    @Test
    void typeMismatchOnOtherParameterGetsValidationFailedEvenWithNotFoundCode() throws Exception {
        ResponseEntity<Object> response = handler.handleArgumentTypeMismatch(mismatch(1, Integer.class), request);

        assertThat(problem(response, HttpStatus.BAD_REQUEST).getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void frameworkServerErrorGetsInternalErrorCode() throws Exception {
        ResponseEntity<Object> response = handler.handleException(new AsyncRequestTimeoutException(), request);

        assertThat(problem(response, HttpStatus.SERVICE_UNAVAILABLE).getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    private static MethodArgumentTypeMismatchException mismatch(int index, Class<?> type) throws Exception {
        Method method = Fixture.class.getDeclaredMethod("annotated", UUID.class, Integer.class);
        return new MethodArgumentTypeMismatchException(
                "abc", type, "param" + index, new MethodParameter(method, index), new IllegalArgumentException());
    }

    private static ProblemDetail problem(ResponseEntity<Object> response, HttpStatus status) {
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        return (ProblemDetail) response.getBody();
    }
}
