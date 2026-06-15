package space.harbour.cloud.payments.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import space.harbour.cloud.payments.shard.ImportRequestRepository;
import space.harbour.cloud.payments.shard.PendingPayment;
import space.harbour.cloud.payments.shard.ShardedPaymentRepository;

import java.util.List;

/**
 * Background worker: drains {@code PENDING} payments from all shards and creates the
 * corresponding entries in the remote Payments API.
 *
 * <p>Runs on a fixed delay and is safe to run repeatedly (or to scale out): claiming
 * uses {@code FOR UPDATE SKIP LOCKED}, so no two passes process the same payment.
 *
 * <p>Per payment:
 * <ul>
 *   <li><b>created</b> → status {@code DONE} (+ remote id), request {@code processed++};</li>
 *   <li><b>permanent 4xx</b> → status {@code FAILED}, request {@code failed++};</li>
 *   <li><b>transient, retries exhausted</b> → requeued to {@code PENDING} for a later
 *       pass, unless it has hit {@code maxAttempts}, in which case it is {@code FAILED}.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "shard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AsyncPaymentWorker {

	private static final Logger log = LoggerFactory.getLogger(AsyncPaymentWorker.class);

	private final ShardedPaymentRepository payments;
	private final ImportRequestRepository requests;
	private final RemotePaymentClient remote;
	private final int batchPerShard;
	private final int maxAttempts;

	public AsyncPaymentWorker(
			ShardedPaymentRepository payments,
			ImportRequestRepository requests,
			RemotePaymentClient remote,
			@org.springframework.beans.factory.annotation.Value("${worker.batch-size:50}") int batchPerShard,
			@org.springframework.beans.factory.annotation.Value("${worker.max-attempts:10}") int maxAttempts) {
		this.payments = payments;
		this.requests = requests;
		this.remote = remote;
		this.batchPerShard = batchPerShard;
		this.maxAttempts = maxAttempts;
	}

	@Scheduled(fixedDelayString = "${worker.interval:1000}")
	public void processPending() {
		try {
			List<PendingPayment> batch = payments.claimPending(batchPerShard);
			if (batch.isEmpty()) {
				return;
			}
			log.debug("claimed {} pending payment(s)", batch.size());
			for (PendingPayment p : batch) {
				process(p);
			}
		} catch (RuntimeException e) {
			// Don't let a transient DB hiccup kill the scheduler; the next tick retries.
			log.warn("worker pass failed, will retry next tick: {}", e.getMessage());
		}
	}

	private void process(PendingPayment p) {
		RemotePaymentClient.Result result = remote.createPayment(p);
		switch (result.outcome()) {
			case CREATED -> {
				payments.markDone(p.storeId(), p.id(), result.remotePaymentId());
				requests.recordResult(p.requestId(), true);
			}
			case PERMANENT_FAILURE -> {
				payments.markFailed(p.storeId(), p.id());
				requests.recordResult(p.requestId(), false);
			}
			case TRANSIENT_FAILURE -> {
				if (p.attempts() >= maxAttempts) {
					log.warn("payment {} exhausted {} attempts; marking FAILED", p.id(), maxAttempts);
					payments.markFailed(p.storeId(), p.id());
					requests.recordResult(p.requestId(), false);
				} else {
					// Leave it for a later pass; the remote may just be temporarily down.
					payments.requeue(p.storeId(), p.id());
				}
			}
		}
	}
}
