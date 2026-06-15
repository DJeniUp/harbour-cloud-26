package space.harbour.cloud.payments.shard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@code import_request} aggregate, which lives ONLY in the
 * metadata DB — a bulk request can fan out across multiple shards, so its rollup
 * counts need a single authoritative home.
 */
@Repository
@ConditionalOnProperty(prefix = "shard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ImportRequestRepository {

	private static final RowMapper<ImportRequestRow> MAPPER = (rs, n) -> new ImportRequestRow(
			(UUID) rs.getObject("id"),
			rs.getString("status"),
			rs.getInt("total"),
			rs.getInt("processed"),
			rs.getInt("failed"));

	private final JdbcTemplate meta;

	public ImportRequestRepository(JdbcTemplate metaJdbcTemplate) {
		this.meta = metaJdbcTemplate;
	}

	/** Creates a {@code PENDING} request with the given payment {@code total}. */
	public void create(UUID id, int total) {
		Timestamp now = Timestamp.from(Instant.now());
		meta.update("""
				INSERT INTO import_request
				    (id, status, total, processed, failed, created_at, updated_at)
				VALUES (?, 'PENDING', ?, 0, 0, ?, ?)
				""", id, total, now, now);
	}

	public Optional<ImportRequestRow> find(UUID id) {
		return meta.query("""
				SELECT id, status, total, processed, failed
				FROM import_request WHERE id = ?
				""", MAPPER, id).stream().findFirst();
	}

	/**
	 * Atomically records one payment outcome: bumps {@code processed} (success) or
	 * {@code failed}, and flips the request to {@code DONE} once
	 * {@code processed + failed == total}. Done in a single UPDATE so concurrent
	 * worker threads can't race the DONE transition.
	 */
	public void recordResult(UUID requestId, boolean success) {
		int p = success ? 1 : 0;
		int f = success ? 0 : 1;
		meta.update("""
				UPDATE import_request
				SET processed = processed + ?,
				    failed = failed + ?,
				    status = CASE WHEN (processed + ? + failed + ?) >= total
				                  THEN 'DONE' ELSE status END,
				    updated_at = ?
				WHERE id = ?
				""", p, f, p, f, Timestamp.from(Instant.now()), requestId);
	}
}
