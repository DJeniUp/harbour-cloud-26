package space.harbour.cloud.payments.shard;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * Holds one {@link JdbcTemplate} (backed by one {@link HikariDataSource}) per shard.
 *
 * <p>Wrapping the per-shard templates in a dedicated type — rather than exposing a
 * raw {@code List<JdbcTemplate>} bean — keeps Spring from confusing the shard list
 * with the standalone metadata {@code JdbcTemplate} bean when autowiring by type.
 *
 * <p>Implements {@link AutoCloseable} so the Hikari pools are released on shutdown
 * (wired via {@code @Bean(destroyMethod = "close")}).
 */
public class ShardTemplates implements AutoCloseable {

	private final List<HikariDataSource> dataSources;
	private final List<JdbcTemplate> templates;

	public ShardTemplates(List<HikariDataSource> dataSources, List<JdbcTemplate> templates) {
		this.dataSources = List.copyOf(dataSources);
		this.templates = List.copyOf(templates);
	}

	/** @return the {@link JdbcTemplate} for shard {@code index} (0-based). */
	public JdbcTemplate forShard(int index) {
		return templates.get(index);
	}

	/** @return every shard's template, in shard-index order — used by the worker's scan. */
	public List<JdbcTemplate> all() {
		return templates;
	}

	public DataSource dataSource(int index) {
		return dataSources.get(index);
	}

	public int size() {
		return templates.size();
	}

	@Override
	public void close() {
		for (HikariDataSource ds : dataSources) {
			ds.close();
		}
	}
}
