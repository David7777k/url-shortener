package io.github.david7777k.trimly.click;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

/**
 * Drains queued clicks into the database in batches.
 *
 * <p>Plain JDBC batch insert rather than JPA: these rows are never read back as
 * entities, and Hibernate cannot batch inserts against an identity column
 * anyway - it has to read each generated key back one statement at a time.
 */
@Component
public class ClickWriter {

    private static final Logger log = LoggerFactory.getLogger(ClickWriter.class);

    private static final String INSERT = """
            insert into click_event (link_id, clicked_at, referrer, user_agent)
            values (?, ?, ?, ?)
            """;

    private final ClickRecorder recorder;
    private final JdbcTemplate jdbcTemplate;
    private final int batchSize;

    public ClickWriter(ClickRecorder recorder,
                       JdbcTemplate jdbcTemplate,
                       @Value("${trimly.clicks.batch-size:500}") int batchSize) {
        this.recorder = recorder;
        this.jdbcTemplate = jdbcTemplate;
        this.batchSize = batchSize;
    }

    /**
     * @return how many events were written
     */
    public int flushOnce() {
        int written = 0;

        List<ClickRecorder.ClickEvent> batch;
        while (!(batch = recorder.drain(batchSize)).isEmpty()) {
            insert(batch);
            written += batch.size();

            if (batch.size() < batchSize) {
                break;
            }
        }

        return written;
    }

    /**
     * Writes whatever is still queued at shutdown.
     *
     * <p>Not a guarantee. A process killed outright loses its queue, and that is
     * the accepted cost of keeping the insert off the redirect thread - the
     * alternative is an outbox table written inside the redirect transaction,
     * which is precisely the latency this design refuses to pay.
     */
    @PreDestroy
    void flushOnShutdown() {
        int written = flushOnce();
        if (written > 0) {
            log.info("Flushed {} click events during shutdown", written);
        }
    }

    private void insert(List<ClickRecorder.ClickEvent> batch) {
        jdbcTemplate.batchUpdate(INSERT, batch, batch.size(), (ps, event) -> {
            ps.setLong(1, event.linkId());
            ps.setTimestamp(2, Timestamp.from(event.clickedAt()));
            setNullable(ps, 3, event.referrer());
            setNullable(ps, 4, event.userAgent());
        });
    }

    private static void setNullable(java.sql.PreparedStatement ps, int index, String value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
