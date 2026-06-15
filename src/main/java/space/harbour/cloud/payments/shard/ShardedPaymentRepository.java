package space.harbour.cloud.payments.shard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plain-JDBC repository over N Postgres shards.
 *
 * <p>Writes and per-payment updates are routed by {@link ShardResolver}: a payment's
 * {@code storeId} picks exactly one shard, so a store's payments are co-located. The
 * worker's scan, by contrast, iterates <em>every</em> shard.
 *
 * <p>Claiming uses {@code SELECT ... FOR UPDATE SKIP LOCKED}: a short transaction per
 * shard locks a batch of {@code PENDING} rows, flips them to {@code PROCESSING}, and
 * commits — so the (possibly slow) remote call happens outside any held lock, and two
 * concurrent / repeated worker passes never grab the same row.
 */
@Repository
@ConditionalOnProperty(prefix = "shard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShardedPaymentRepository {

	private static final String INSERT = """
			INSERT INTO payment
			    (id, request_id, store_id, coffee_type, price, currency,
			     loyalty_card_id, status, attempts, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
			ON CONFLICT (id) DO NOTHING
			""";

	private static final String CLAIM_SELECT = """
			SELECT id, request_id, store_id, coffee_type, price, currency,
			       loyalty_card_id, attempts
			FROM payment
			WHERE status = 'PENDING'
			ORDER BY created_at
			LIMIT ?
			FOR UPDATE SKIP LOCKED
			""";

	private static final String MARK_PROCESSING = """
			UPDATE payment
			SET status = 'PROCESSING', attempts = attempts + 1, updated_at = ?
			WHERE id = ?
			""";

	private final ShardTemplates shards;
	private final ShardResolver resolver;

	public ShardedPaymentRepository(ShardTemplates shards, ShardResolver resolver) {
		this.shards = shards;
		this.resolver = resolver;
	}

	/** Inserts a payment into its shard with status {@code PENDING}. Idempotent on id. */
	public void insertPending(NewPayment p) {
		JdbcTemplate jdbc = shards.forShard(resolver.shardOf(p.storeId()));
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update(INSERT,
				p.id(), p.requestId(), p.storeId(), p.coffeeType(),
				p.price(), p.currency(), p.loyaltyCardId(), now, now);
	}

	/**
	 * Atomically claims up to {@code batchPerShard} pending payments from <em>every</em>
	 * shard, flipping them to {@code PROCESSING}. Safe to call repeatedly / concurrently.
	 */
	public List<PendingPayment> claimPending(int batchPerShard) {
		List<PendingPayment> claimed = new ArrayList<>();
		for (int shard = 0; shard < shards.size(); shard++) {
			claimed.addAll(claimFromShard(shard, batchPerShard));
		}
		return claimed;
	}

	private List<PendingPayment> claimFromShard(int shard, int limit) {
		JdbcTemplate jdbc = shards.forShard(shard);
		return jdbc.execute((java.sql.Connection con) -> {
			boolean previousAutoCommit = con.getAutoCommit();
			con.setAutoCommit(false);
			try {
				List<PendingPayment> rows = new ArrayList<>();
				try (PreparedStatement select = con.prepareStatement(CLAIM_SELECT)) {
					select.setInt(1, limit);
					try (ResultSet rs = select.executeQuery()) {
						while (rs.next()) {
							rows.add(new PendingPayment(
									shard,
									(UUID) rs.getObject("id"),
									(UUID) rs.getObject("request_id"),
									rs.getString("store_id"),
									rs.getString("coffee_type"),
									rs.getBigDecimal("price"),
									rs.getString("currency"),
									rs.getString("loyalty_card_id"),
									rs.getInt("attempts") + 1)); // reflects the increment below
						}
					}
				}
				if (!rows.isEmpty()) {
					Timestamp now = Timestamp.from(Instant.now());
					try (PreparedStatement update = con.prepareStatement(MARK_PROCESSING)) {
						for (PendingPayment row : rows) {
							update.setTimestamp(1, now);
							update.setObject(2, row.id());
							update.addBatch();
						}
						update.executeBatch();
					}
				}
				con.commit();
				return rows;
			} catch (RuntimeException | java.sql.SQLException e) {
				con.rollback();
				throw e;
			} finally {
				con.setAutoCommit(previousAutoCommit);
			}
		});
	}

	/** Marks a claimed payment {@code DONE} and records the remote payment id. */
	public void markDone(String storeId, UUID id, String remotePaymentId) {
		jdbcFor(storeId).update("""
				UPDATE payment
				SET status = 'DONE', remote_payment_id = ?, updated_at = ?
				WHERE id = ?
				""", remotePaymentId, Timestamp.from(Instant.now()), id);
	}

	/** Marks a claimed payment permanently {@code FAILED}. */
	public void markFailed(String storeId, UUID id) {
		jdbcFor(storeId).update("""
				UPDATE payment
				SET status = 'FAILED', updated_at = ?
				WHERE id = ?
				""", Timestamp.from(Instant.now()), id);
	}

	/** Returns a payment to {@code PENDING} so a later worker pass retries it. */
	public void requeue(String storeId, UUID id) {
		jdbcFor(storeId).update("""
				UPDATE payment
				SET status = 'PENDING', updated_at = ?
				WHERE id = ?
				""", Timestamp.from(Instant.now()), id);
	}

	/** Counts payments by status on the shard owning {@code storeId} (test/diagnostic aid). */
	public long countByStatus(String storeId, String status) {
		Long n = jdbcFor(storeId).queryForObject(
				"SELECT count(*) FROM payment WHERE store_id = ? AND status = ?",
				Long.class, storeId, status);
		return n == null ? 0L : n;
	}

	private JdbcTemplate jdbcFor(String storeId) {
		return shards.forShard(resolver.shardOf(storeId));
	}
}
