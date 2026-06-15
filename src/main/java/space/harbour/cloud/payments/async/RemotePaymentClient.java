package space.harbour.cloud.payments.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import space.harbour.cloud.payments.shard.PendingPayment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creates an entry in the remote Payments API for one claimed payment, reusing the
 * homework-1 reliability recipe:
 *
 * <ul>
 *   <li><b>Idempotency</b> — sends {@code Idempotency-Key = payment.id} and
 *       {@code Store-Id = storeId}, so a retry or a worker restart never creates a
 *       duplicate remote entry; the server replays the original with {@code 200}.</li>
 *   <li><b>Retry with capped exponential backoff + full jitter</b> for transient
 *       failures (I/O, {@code 408}/{@code 429}/{@code 5xx}); {@code Retry-After} honoured.</li>
 *   <li><b>Never retry permanent {@code 4xx}</b> (bad data / misconfig).</li>
 *   <li>Follows {@code 302} from the redirect load balancer manually (Java's
 *       {@link HttpClient} will not auto-redirect a POST).</li>
 * </ul>
 *
 * <p>The classification (created / permanent / transient) is returned to the worker,
 * which decides whether to mark the payment DONE, FAILED, or requeue it.
 */
@Component
@ConditionalOnProperty(prefix = "shard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RemotePaymentClient {

	private static final Logger log = LoggerFactory.getLogger(RemotePaymentClient.class);

	public enum Outcome { CREATED, PERMANENT_FAILURE, TRANSIENT_FAILURE }

	public record Result(Outcome outcome, String remotePaymentId) {
		static Result created(String id) {
			return new Result(Outcome.CREATED, id);
		}

		static Result permanent() {
			return new Result(Outcome.PERMANENT_FAILURE, null);
		}

		static Result transient_() {
			return new Result(Outcome.TRANSIENT_FAILURE, null);
		}
	}

	private static final int MAX_ATTEMPTS = 5;
	private static final int MAX_REDIRECTS = 5;
	private static final Duration BASE_BACKOFF = Duration.ofMillis(200);
	private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient http;
	private final ObjectMapper json;
	private final URI baseUrl;

	public RemotePaymentClient(ObjectMapper json,
							   @Value("${remote.base-url:http://localhost:8080}") String baseUrl) {
		this.json = json;
		this.baseUrl = URI.create(baseUrl);
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.build();
	}

	/** Creates (or idempotently replays) the remote payment for {@code p}. */
	public Result createPayment(PendingPayment p) {
		URI target = baseUrl.resolve("/api/v1/payments");
		String body = bodyJson(p);

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				HttpResponse<String> resp = sendFollowingRedirects(p, target, body);
				int code = resp.statusCode();

				if (code == 201 || code == 200) {
					return Result.created(extractPaymentId(resp.body()));
				}
				if (isRetryable(code)) {
					if (attempt < MAX_ATTEMPTS) {
						sleep(backoff(attempt, resp));
						continue;
					}
					log.warn("payment {} gave up after {} attempts (last {})", p.id(), attempt, code);
					return Result.transient_();
				}
				log.warn("payment {} permanent failure {}: {}", p.id(), code, oneLine(resp.body()));
				return Result.permanent();

			} catch (java.io.IOException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				if (attempt < MAX_ATTEMPTS) {
					sleep(backoff(attempt, null));
					continue;
				}
				log.warn("payment {} gave up after {} attempts ({})", p.id(), attempt,
						e.getClass().getSimpleName());
				return Result.transient_();
			}
		}
		return Result.transient_();
	}

	private HttpResponse<String> sendFollowingRedirects(PendingPayment p, URI uri, String body)
			throws java.io.IOException, InterruptedException {
		URI current = uri;
		HttpResponse<String> resp = null;
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			resp = http.send(buildRequest(p, current, body), HttpResponse.BodyHandlers.ofString());
			if (!isRedirect(resp.statusCode())) {
				return resp;
			}
			Optional<String> location = resp.headers().firstValue("Location");
			if (location.isEmpty()) {
				return resp;
			}
			current = current.resolve(location.get());
		}
		return resp;
	}

	private HttpRequest buildRequest(PendingPayment p, URI uri, String body) {
		return HttpRequest.newBuilder(uri)
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.header("Store-Id", p.storeId())
				.header("Idempotency-Key", p.id().toString())
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
	}

	private String bodyJson(PendingPayment p) {
		ObjectNode node = json.createObjectNode();
		node.put("coffeeType", p.coffeeType());
		node.put("price", p.price());
		node.put("currency", p.currency());
		if (p.loyaltyCardId() != null && !p.loyaltyCardId().isBlank()) {
			node.put("loyaltyCardId", p.loyaltyCardId());
		}
		return json.writeValueAsString(node); // Jackson 3 throws an unchecked JacksonException
	}

	private String extractPaymentId(String responseBody) {
		try {
			JsonNode node = json.readTree(responseBody);
			JsonNode id = node.get("paymentId");
			return id != null && !id.isNull() ? id.asString() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean isRedirect(int code) {
		return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
	}

	private static boolean isRetryable(int code) {
		return code == 408 || code == 429 || (code >= 500 && code <= 599);
	}

	private Duration backoff(int attempt, HttpResponse<String> resp) {
		if (resp != null && resp.statusCode() == 429) {
			Optional<String> ra = resp.headers().firstValue("Retry-After");
			if (ra.isPresent()) {
				try {
					return Duration.ofSeconds(Long.parseLong(ra.get().trim()));
				} catch (NumberFormatException ignore) {
					// fall through to computed backoff
				}
			}
		}
		long expMs = BASE_BACKOFF.toMillis() * (1L << (attempt - 1));
		long cappedMs = Math.min(expMs, MAX_BACKOFF.toMillis());
		long jittered = ThreadLocalRandom.current().nextLong(0, cappedMs + 1); // full jitter
		return Duration.ofMillis(jittered);
	}

	private static void sleep(Duration d) {
		try {
			Thread.sleep(d.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static String oneLine(String body) {
		if (body == null) {
			return "";
		}
		String s = body.replace("\n", " ").trim();
		return s.length() > 200 ? s.substring(0, 200) + "..." : s;
	}
}
