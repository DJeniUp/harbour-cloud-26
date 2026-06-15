package space.harbour.cloud.payments.shard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Creates the schema on startup: {@code schema-shard.sql} against every shard and
 * {@code schema-meta.sql} against the metadata DB. The scripts use
 * {@code CREATE TABLE IF NOT EXISTS}, so this is safe to run on every boot.
 *
 * <p>Because Spring Boot's docker-compose readiness wait is disabled in this project
 * (it would deadlock on the Toxiproxy self-proxy), a freshly started Postgres may not
 * yet be accepting connections; each datasource is therefore retried for a short
 * window before giving up.
 */
@Component
@ConditionalOnProperty(prefix = "shard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchemaInitializer implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

	private static final int MAX_ATTEMPTS = 30;
	private static final long RETRY_DELAY_MS = 1_000;

	private final ShardTemplates shards;
	private final JdbcTemplate metaJdbcTemplate;

	public SchemaInitializer(ShardTemplates shards, JdbcTemplate metaJdbcTemplate) {
		this.shards = shards;
		this.metaJdbcTemplate = metaJdbcTemplate;
	}

	@Override
	public void run(String... args) {
		for (int i = 0; i < shards.size(); i++) {
			apply("schema-shard.sql", shards.dataSource(i), "shard-" + i);
		}
		apply("schema-meta.sql", metaJdbcTemplate.getDataSource(), "meta");
	}

	private void apply(String script, DataSource dataSource, String label) {
		ResourceDatabasePopulator populator =
				new ResourceDatabasePopulator(new ClassPathResource(script));
		RuntimeException last = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				DatabasePopulatorUtils.execute(populator, dataSource);
				log.info("schema initialised on {} ({})", label, script);
				return;
			} catch (RuntimeException e) {
				last = e;
				log.info("{} not ready yet (attempt {}/{}): {}",
						label, attempt, MAX_ATTEMPTS, e.getMessage());
				sleep();
			}
		}
		throw new IllegalStateException("could not initialise schema on " + label, last);
	}

	private static void sleep() {
		try {
			Thread.sleep(RETRY_DELAY_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for database", e);
		}
	}
}
