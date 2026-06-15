package space.harbour.cloud.payments.shard;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Static shard topology, bound from {@code shard.*}.
 *
 * <p>There is no resharding: the number of shards and their connection details are
 * fixed at boot. {@code shard.count} must match the size of {@code shard.datasources}.
 *
 * <pre>
 * shard.count=2
 * shard.datasources[0].url=jdbc:postgresql://localhost:5432/payments
 * shard.datasources[0].username=app
 * shard.datasources[0].password=app
 * shard.datasources[1].url=jdbc:postgresql://localhost:5433/payments
 * ...
 * </pre>
 */
@ConfigurationProperties(prefix = "shard")
public class ShardProperties {

	/**
	 * Master switch for the whole sharded store + async pipeline. Defaults to on;
	 * the test profile sets it to {@code false} so the slice of context that needs a
	 * live Postgres never boots during unit tests.
	 */
	private boolean enabled = true;

	/** Number of shards. Must equal {@link #datasources}.size(). */
	private int count;

	private List<DataSource> datasources = new ArrayList<>();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getCount() {
		return count;
	}

	public void setCount(int count) {
		this.count = count;
	}

	public List<DataSource> getDatasources() {
		return datasources;
	}

	public void setDatasources(List<DataSource> datasources) {
		this.datasources = datasources;
	}

	/** One shard's JDBC coordinates. */
	public static class DataSource {
		private String url;
		private String username;
		private String password;

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}
	}
}
