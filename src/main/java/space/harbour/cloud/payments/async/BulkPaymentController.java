package space.harbour.cloud.payments.async;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asynchronous bulk payment API (homework 3).
 *
 * <p>Distinct from the synchronous single-payment {@code PaymentController}: a bulk
 * request is accepted with {@code 202 Accepted} and processed in the background by
 * {@link AsyncPaymentWorker}. Callers poll the request-status endpoint to follow it.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Validated
@ConditionalOnProperty(prefix = "shard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BulkPaymentController {

	private final BulkPaymentService service;

	public BulkPaymentController(BulkPaymentService service) {
		this.service = service;
	}

	/**
	 * Accepts a batch of payments for asynchronous processing.
	 *
	 * @return {@code 202 Accepted} with {@code {"requestId": "<uuid>"}}
	 */
	@PostMapping("/bulk")
	public ResponseEntity<Map<String, String>> submitBulk(
			@RequestBody @NotEmpty(message = "at least one payment is required")
			List<@Valid BulkPaymentItem> items) {

		UUID requestId = service.accept(items);
		return ResponseEntity.accepted().body(Map.of("requestId", requestId.toString()));
	}

	/**
	 * Returns the aggregate status of a previously submitted bulk request.
	 */
	@GetMapping("/requests/{id}")
	public RequestStatusResponse requestStatus(@PathVariable String id) {
		UUID requestId = parse(id);
		return service.status(requestId)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "No request with id " + id));
	}

	private static UUID parse(String id) {
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No request with id " + id);
		}
	}
}
