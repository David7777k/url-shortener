package io.github.david7777k.trimly.click;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Calls the writer on a timer.
 *
 * <p>Separate from {@link ClickWriter} so the schedule can be switched off
 * without removing the writer itself - tests need to drain the queue at a
 * moment they choose, not whenever a timer fires mid-assertion.
 */
@Component
@ConditionalOnProperty(name = "trimly.clicks.enabled", matchIfMissing = true)
public class ClickFlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClickFlushScheduler.class);

    private final ClickWriter writer;

    public ClickFlushScheduler(ClickWriter writer) {
        this.writer = writer;
    }

    @Scheduled(
            fixedDelayString = "${trimly.clicks.flush-interval:PT1S}",
            initialDelayString = "${trimly.clicks.initial-delay:PT1S}")
    public void flush() {
        try {
            writer.flushOnce();
        } catch (RuntimeException e) {
            // An exception escaping a scheduled method stops the task for good,
            // turning one bad batch into a permanently dead writer.
            log.error("Failed to flush click events", e);
        }
    }
}
