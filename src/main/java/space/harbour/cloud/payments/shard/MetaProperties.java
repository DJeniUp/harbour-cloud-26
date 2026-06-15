package space.harbour.cloud.payments.shard;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection details for the <em>metadata</em> database, bound from
 * {@code meta.datasource.*}.
 *
 * <p>The metadata DB is deliberately NOT sharded: a single bulk request can span
 * many stores and therefore many shards, so its aggregate status
 * ({@code import_request}) needs one authoritative home.
 */
@ConfigurationProperties(prefix = "meta.datasource")
public class MetaProperties {

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
