package kh.mentor.trading.alerts;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Disabled by default. Runs only after operator configures bot credentials. */
@Component
@ConditionalOnProperty(prefix="trading.alert", name="delivery-enabled", havingValue="true")
public class TelegramDispatchScheduler {
    private final AlertDispatcher dispatcher;
    public TelegramDispatchScheduler(AlertDispatcher dispatcher) { this.dispatcher = dispatcher; }
    @Scheduled(fixedDelayString="${trading.alert.dispatch-interval-ms:60000}")
    public void dispatch() { dispatcher.dispatch(20); }
}
