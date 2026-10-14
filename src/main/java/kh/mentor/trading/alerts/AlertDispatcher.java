package kh.mentor.trading.alerts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded sender: persist ACK, or schedule retries without losing the event. */
@Service
public class AlertDispatcher {
    private static final long LEASE_MS = 90_000L;
    private final AlertRepository outbox;
    private final TelegramTransport telegram;
    private final int maxAttempts;

    public AlertDispatcher(AlertRepository outbox, TelegramTransport telegram,
        @Value("${trading.alert.max-attempts:5}") int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 20) throw new IllegalArgumentException("Invalid max attempts");
        this.outbox = outbox;
        this.telegram = telegram;
        this.maxAttempts = maxAttempts;
    }

    /** One bounded poll; safe to call concurrently due to DB claim/lease predicates. */
    public int dispatch(int maxItems) {
        if (maxItems < 1 || maxItems > 100) throw new IllegalArgumentException("Invalid batch size");
        long now = System.currentTimeMillis();
        outbox.recoverExpired(now, maxAttempts);
        int handled = 0;
        for (int i = 0; i < maxItems; i++) {
            ClaimedAlert claim = outbox.claim(System.currentTimeMillis(), LEASE_MS, maxAttempts);
            if (claim == null) break;
            handled++;
            try {
                TelegramTransport.DeliveryReceipt receipt = telegram.send(claim.payload());
                if (!outbox.markSent(claim, System.currentTimeMillis(), receipt.messageId())) {
                    throw new IllegalStateException("Delivery ACK could not be recorded; check outbox");
                }
            } catch (TelegramDeliveryException e) {
                outbox.markFailed(claim, System.currentTimeMillis(), maxAttempts,
                    e.permanent(), e.errorCode());
            } catch (RuntimeException e) {
                // Unknown failures may be transient. Log only fixed diagnostic categories.
                outbox.markFailed(claim, System.currentTimeMillis(), maxAttempts,
                    false, "UNEXPECTED_DELIVERY_ERROR");
            }
        }
        return handled;
    }
}
