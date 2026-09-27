package io.github.david7777k.trimly.click;

import io.github.david7777k.trimly.common.error.LinkNotFoundException;
import io.github.david7777k.trimly.link.LinkRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

@Service
public class ClickStatsService {

    /** Bounded so one request cannot ask for every row ever recorded. */
    private static final int MAX_DAYS = 365;
    private static final int TOP_REFERRERS = 10;

    private final LinkRepository linkRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ClickStatsService(LinkRepository linkRepository, JdbcTemplate jdbcTemplate, Clock clock) {
        this.linkRepository = linkRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ClickStatsResponse statsFor(String code, int days) {
        long linkId = linkRepository.findByCode(code)
                .orElseThrow(() -> new LinkNotFoundException(code))
                .getId();

        Timestamp since = Timestamp.from(
                clock.instant().minus(Duration.ofDays(Math.min(days, MAX_DAYS))));

        Long total = jdbcTemplate.queryForObject(
                "select count(*) from click_event where link_id = ?", Long.class, linkId);

        List<ClickStatsResponse.DailyCount> byDay = jdbcTemplate.query("""
                select date_trunc('day', clicked_at)::date as day, count(*) as clicks
                from click_event
                where link_id = ? and clicked_at >= ?
                group by day
                order by day
                """,
                (rs, row) -> new ClickStatsResponse.DailyCount(
                        rs.getObject("day", LocalDate.class), rs.getLong("clicks")),
                linkId, since);

        // NULL referrers are grouped as "direct" rather than dropped: traffic
        // with no referrer is a real category, not missing data.
        List<ClickStatsResponse.ReferrerCount> byReferrer = jdbcTemplate.query("""
                select coalesce(referrer, 'direct') as source, count(*) as clicks
                from click_event
                where link_id = ? and clicked_at >= ?
                group by source
                order by clicks desc, source
                limit ?
                """,
                (rs, row) -> new ClickStatsResponse.ReferrerCount(
                        rs.getString("source"), rs.getLong("clicks")),
                linkId, since, TOP_REFERRERS);

        return new ClickStatsResponse(code, total == null ? 0 : total, byDay, byReferrer);
    }
}
