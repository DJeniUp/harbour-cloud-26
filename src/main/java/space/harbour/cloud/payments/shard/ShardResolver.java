package space.harbour.cloud.payments.shard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Maps a {@code storeId} to a shard index.
 *
 * <p>Keying on {@code storeId} co-locates all of a store's payments on a single
 * shard. {@link String#hashCode()} is specified by the JLS, so the mapping is stable
 * across JVMs and restarts; {@link Math#floorMod} (rather than {@code %}) keeps the
 * result in {@code [0, count)} even when the hash is negative.
 */
@Component
@ConditionalOnProperty(prefix = "shard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShardResolver {

	private final int shardCount;

	public ShardResolver(ShardProperties props) {
		this.shardCount = props.getCount();
	}

	public int shardOf(String storeId) {
		return Math.floorMod(storeId.hashCode(), shardCount);
	}

	public int shardCount() {
		return shardCount;
	}
}
