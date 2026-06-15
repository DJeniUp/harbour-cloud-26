package space.harbour.cloud.payments.async;

import space.harbour.cloud.payments.shard.ImportRequestRow;

/**
 * The body of {@code GET /api/v1/payments/requests/{id}}.
 */
public record RequestStatusResponse(
		String requestId,
		String status,
		int total,
		int processed,
		int failed) {

	static RequestStatusResponse from(ImportRequestRow row) {
		return new RequestStatusResponse(
				row.id().toString(), row.status(), row.total(), row.processed(), row.failed());
	}
}
