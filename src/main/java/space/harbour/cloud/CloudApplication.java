package space.harbour.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The payments application entry point.
 *
 * <p>{@code DataSourceAutoConfiguration} is excluded because there is no single
 * primary datasource: the payment store is sharded across N Postgres instances
 * (plus a separate metadata DB), and each {@link javax.sql.DataSource} is built
 * programmatically in {@code space.harbour.cloud.payments.shard.ShardConfig}.
 * {@code @EnableScheduling} drives the background async-payment worker.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableScheduling
public class CloudApplication {

	public static void main(String[] args) {
		SpringApplication.run(CloudApplication.class, args);
	}

}
