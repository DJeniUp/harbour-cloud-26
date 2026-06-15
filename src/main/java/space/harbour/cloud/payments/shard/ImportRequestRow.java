package space.harbour.cloud.payments.shard;

import java.util.UUID;

/**
 * Aggregate status of one bulk request, stored in the (non-sharded) metadata DB.
 */
public record ImportRequestRow(
		UUID id,
		String status,
		int total,
		int processed,
		int failed) {
}
