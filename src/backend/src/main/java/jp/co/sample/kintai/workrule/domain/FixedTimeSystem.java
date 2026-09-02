package jp.co.sample.kintai.workrule.domain;

import java.time.Duration;
import java.time.LocalTime;

/**
 * 固定時間制。始業・終業・休憩が就業規則で決まっている。
 *
 * @param scheduledStart 始業時刻
 * @param scheduledEnd   終業時刻。始業以下なら日をまたぐ勤務
 * @param scheduledBreak 所定休憩
 */
public record FixedTimeSystem(LocalTime scheduledStart, LocalTime scheduledEnd,
                              Duration scheduledBreak) implements WorkingTimeSystem {

    /** 労基法 34 条。労働時間が 6 時間を超えるなら休憩 45 分以上。 */
    private static final Duration SIX_HOURS = Duration.ofHours(6);
    private static final Duration BREAK_OVER_SIX_HOURS = Duration.ofMinutes(45);

    public FixedTimeSystem {
        if (scheduledStart == null || scheduledEnd == null || scheduledBreak == null) {
            throw new IllegalArgumentException("固定時間制の項目に null は許されません");
        }
        if (scheduledBreak.isNegative()) {
            throw new IllegalArgumentException("所定休憩を負にはできません: " + scheduledBreak);
        }
        Duration working = workingTimeOf(scheduledStart, scheduledEnd, scheduledBreak);
        if (!working.isPositive()) {
            throw new IllegalArgumentException(
                    "所定労働時間が 0 以下です（休憩が拘束時間を超えています）: " + working);
        }
        if (working.compareTo(SIX_HOURS) > 0
                && scheduledBreak.compareTo(BREAK_OVER_SIX_HOURS) < 0) {
            throw new IllegalArgumentException(
                    "所定労働時間が 6 時間を超える場合、休憩は 45 分以上必要です（労基法 34 条）: "
                            + "所定 %s / 休憩 %s".formatted(working, scheduledBreak));
        }
    }

    /**
     * 所定労働時間。拘束時間から所定休憩を差し引く。
     *
     * <p>終業が始業以下なら日をまたぐ勤務とみなして 24 時間を足す。
     */
    public Duration scheduledWorkingTime() {
        return workingTimeOf(scheduledStart, scheduledEnd, scheduledBreak);
    }

    private static Duration workingTimeOf(LocalTime start, LocalTime end, Duration breakTime) {
        Duration span = Duration.between(start, end);
        if (!span.isPositive()) {
            span = span.plusDays(1);
        }
        return span.minus(breakTime);
    }
}
