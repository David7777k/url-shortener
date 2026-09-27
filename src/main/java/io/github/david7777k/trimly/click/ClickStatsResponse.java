package io.github.david7777k.trimly.click;

import java.time.LocalDate;
import java.util.List;

public record ClickStatsResponse(
        String code,
        long totalClicks,
        List<DailyCount> byDay,
        List<ReferrerCount> topReferrers) {

    public record DailyCount(LocalDate day, long clicks) {
    }

    public record ReferrerCount(String source, long clicks) {
    }
}
