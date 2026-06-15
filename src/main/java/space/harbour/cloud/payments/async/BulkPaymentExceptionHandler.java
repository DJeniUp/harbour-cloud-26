package space.harbour.cloud.payments.async;

import jakarta.validation.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Turns bulk-request validation failures into clean {@code 400 Bad Request} responses.
 * Scoped to {@link BulkPaymentController} so it never interferes with the synchronous
 * single-payment controller's own handler.
 */
@RestControllerAdvice(assignableTypes = BulkPaymentController.class)
@ConditionalOnProperty(prefix = "shard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BulkPaymentExceptionHandler {

	/** Empty array / per-element constraints — surfaced as method-validation violations. */
	@ExceptionHandler(ConstraintViolationException.class)
	public ProblemDetail onConstraintViolation(ConstraintViolationException ex) {
		String detail = ex.getConstraintViolations().stream()
				.map(v -> v.getPropertyPath() + ": " + v.getMessage())
				.collect(Collectors.joining("; "));
		return problem(detail.isEmpty() ? "Invalid bulk request" : detail);
	}

	/** Invalid JSON body fields when bound as @Valid on the request body. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail onBodyValidation(MethodArgumentNotValidException ex) {
		String detail = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + ": " + error.getDefaultMessage())
				.collect(Collectors.joining("; "));
		return problem(detail.isEmpty() ? "Invalid bulk request" : detail);
	}

	private ProblemDetail problem(String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
		problem.setTitle("Bulk payment request failed");
		return problem;
	}
}
