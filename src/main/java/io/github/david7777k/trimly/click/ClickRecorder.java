package io.github.david7777k.trimly.click;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Accepts click events from the redirect and hands them to a writer later.
 *
 * <p>The redirect is the one path in this service that has to be fast, so it
 * must not wait for an insert. {@link #record} only puts an object on a queue.
 *
 * <p>When the queue is full, events are dropped rather than made to wait. That
 * is the whole design in one decision: analytics are worth less than the
 * redirect they describe, so under pressure the statistics degrade and the
 * redirect does not. Drops are counted, because a silently lossy counter is
 * worse than a visibly lossy one.
 */
@Component
public class ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);

    private static final int MAX_HEADER_LENGTH = 512;

    private final BlockingQueue<ClickEvent> queue;
    private final Clock clock;
    private final AtomicLong dropped = new AtomicLong();

    public ClickRecorder(Clock clock, @Value("${trimly.clicks.queue-capacity:10000}") int capacity) {
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public void record(long linkId, String referrer, String userAgent) {
        ClickEvent event = new ClickEvent(
                linkId, clock.instant(), truncate(referrer), truncate(userAgent));

        // offer, not put: put would block the redirect thread once the queue
        // filled, which is exactly what this class exists to avoid.
        if (!queue.offer(event)) {
            long total = dropped.incrementAndGet();
            if (total % 1000 == 1) {
                log.warn("Click queue full, dropped {} events so far", total);
            }
        }
    }

    /**
     * Removes up to {@code limit} events for writing.
     *
     * <p>Draining rather than taking one at a time: the point of the queue is
     * to turn many small inserts into few large ones.
     */
    List<ClickEvent> drain(int limit) {
        List<ClickEvent> batch = new ArrayList<>(Math.min(limit, queue.size()));
        queue.drainTo(batch, limit);
        return batch;
    }

    /** Events discarded because the queue was full. Exposed for tests and logs. */
    public long droppedCount() {
        return dropped.get();
    }

    int queued() {
        return queue.size();
    }

    private static String truncate(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        return header.length() <= MAX_HEADER_LENGTH
                ? header
                : header.substring(0, MAX_HEADER_LENGTH);
    }

    public record ClickEvent(long linkId, Instant clickedAt, String referrer, String userAgent) {
    }
}
