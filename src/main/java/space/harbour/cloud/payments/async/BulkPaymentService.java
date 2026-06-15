package space.harbour.cloud.payments.async;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import space.harbour.cloud.payments.shard.ImportRequestRepository;
import space.harbour.cloud.payments.shard.ImportRequestRow;
import space.harbour.cloud.payments.shard.NewPayment;
import space.harbour.cloud.payments.shard.ShardedPaymentRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accepts a bulk request and persists it for asynchronous processing — it does NOT
 * call the remote system inline. It:
 * <ol>
 *   <li>creates one {@code import_request} row (status {@code PENDING}) in the
 *       metadata DB, and</li>
 *   <li>inserts each payment as {@code PENDING} into its shard, linked by
 *       {@code request_id}, with a stable id used later as the remote idempotency key.</li>
 * </ol>
 * The {@link AsyncPaymentWorker} picks the payments up from there.
 */
@Service
@ConditionalOnProperty(prefix = "shard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BulkPaymentService {

	private final ShardedPaymentRepository payments;
	private final ImportRequestRepository requests;

	public BulkPaymentService(ShardedPaymentRepository payments, ImportRequestRepository requests) {
		this.payments = payments;
		this.requests = requests;
	}

	/**
	 * Records a bulk request and returns its id. The work happens later, in the worker.
	 *
	 * @return the generated request id
	 */
	public UUID accept(List<BulkPaymentItem> items) {
		UUID requestId = UUID.randomUUID();
		requests.create(requestId, items.size());

		for (BulkPaymentItem item : items) {
			UUID paymentId = paymentId(item);
			payments.insertPending(new NewPayment(
					paymentId,
					requestId,
					item.storeId(),
					item.coffeeType().name(),
					item.price(),
					item.currency(),
					item.loyaltyCardId()));
		}
		return requestId;
	}

	public Optional<RequestStatusResponse> status(UUID requestId) {
		return requests.find(requestId).map(this::toResponse);
	}

	private RequestStatusResponse toResponse(ImportRequestRow row) {
		return RequestStatusResponse.from(row);
	}

	/**
	 * A stable payment id. When the caller supplies an {@code idempotencyKey}, the id
	 * is derived deterministically from {@code (storeId, idempotencyKey)} so that
	 * re-submitting the same bulk request reuses the same id — and therefore the same
	 * remote Idempotency-Key — making the whole pipeline duplicate-safe. Otherwise a
	 * random id is used.
	 */
	private static UUID paymentId(BulkPaymentItem item) {
		if (item.idempotencyKey() != null && !item.idempotencyKey().isBlank()) {
			String seed = item.storeId() + "|" + item.idempotencyKey();
			return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
		}
		return UUID.randomUUID();
	}
}
