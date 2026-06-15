package space.harbour.cloud.payments.shard;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the datasources programmatically — there is no single primary datasource,
 * so Spring Boot's {@code DataSourceAutoConfiguration} is excluded
 * (see {@code CloudApplication}).
 *
 * <p>Produces:
 * <ul>
 *   <li>a {@link ShardTemplates} bean holding one {@link HikariDataSource} +
 *       {@link JdbcTemplate} per shard, and</li>
 *   <li>a separate {@code metaJdbcTemplate} for the non-sharded metadata DB.</li>
 * </ul>
 *
 * <p>The whole slice is gated on {@code shard.enabled} (default {@code true}) so unit
 * tests can run without a live Postgres.
 */
@Configuration
@EnableConfigurationProperties({ShardProperties.class, MetaProperties.class})
@ConditionalOnProperty(prefix = "shard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShardConfig {

	@Bean(destroyMethod = "close")
	public ShardTemplates shardTemplates(ShardProperties props) {
		List<ShardProperties.DataSource> configs = props.getDatasources();
		if (configs.isEmpty()) {
			throw new IllegalStateException(
					"No shard datasources configured. Set shard.datasources[0..N-1].*");
		}
		if (props.getCount() != configs.size()) {
			throw new IllegalStateException(
					"shard.count (" + props.getCount() + ") must equal the number of "
							+ "shard.datasources entries (" + configs.size() + ")");
		}

		List<HikariDataSource> dataSources = new ArrayList<>(configs.size());
		List<JdbcTemplate> templates = new ArrayList<>(configs.size());
		for (int i = 0; i < configs.size(); i++) {
			ShardProperties.DataSource c = configs.get(i);
			HikariDataSource ds = hikari(c.getUrl(), c.getUsername(), c.getPassword(), "shard-" + i);
			dataSources.add(ds);
			templates.add(new JdbcTemplate(ds));
		}
		return new ShardTemplates(dataSources, templates);
	}

	@Bean(destroyMethod = "close")
	public HikariDataSource metaDataSource(MetaProperties props) {
		return hikari(props.getUrl(), props.getUsername(), props.getPassword(), "meta");
	}

	@Bean
	public JdbcTemplate metaJdbcTemplate(HikariDataSource metaDataSource) {
		return new JdbcTemplate(metaDataSource);
	}

	private static HikariDataSource hikari(String url, String username, String password, String poolName) {
		HikariDataSource ds = new HikariDataSource();
		ds.setJdbcUrl(url);
		ds.setUsername(username);
		ds.setPassword(password);
		ds.setPoolName(poolName);
		ds.setMaximumPoolSize(5);
		ds.setConnectionTimeout(5_000);
		return ds;
	}
}
