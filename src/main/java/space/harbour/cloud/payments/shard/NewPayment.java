package space.harbour.cloud.payments.shard;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A payment about to be inserted into its shard with status {@code PENDING}.
 *
 * <p>{@code id} is generated up front and later sent to the remote system as the
 * {@code Idempotency-Key}, so worker retries / restarts can never create a duplicate
 * remote entry (the homework-1 reliability guarantee, carried into async processing).
 */
public record NewPayment(
		UUID id,
		UUID requestId,
		String storeId,
		String coffeeType,
		BigDecimal price,
		String currency,
		String loyaltyCardId) {
}
