package space.harbour.cloud.payments.shard;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A {@code PENDING} payment claimed from a shard by the background worker.
 *
 * <p>{@code shard} records which shard it came from so updates land on the same
 * datasource without re-resolving (though re-resolving by {@code storeId} would
 * pick the same shard anyway).
 */
public record PendingPayment(
		int shard,
		UUID id,
		UUID requestId,
		String storeId,
		String coffeeType,
		BigDecimal price,
		String currency,
		String loyaltyCardId,
		int attempts) {
}
