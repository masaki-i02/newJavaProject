package jp.co.sample.kintai.workrule.domain;

import java.time.Duration;
import java.time.LocalTime;

import jp.co.sample.kintai.shared.domain.BusinessRuleViolationException;

/**
 * 固定時間制。始業・終業・休憩が就業規則で決まっている。
 *
 * @param scheduledStart 始業時刻
 * @param scheduledEnd   終業時刻。始業以下なら日をまたぐ勤務
 * @param scheduledBreak 所定休憩
 */
public record FixedTimeSystem(LocalTime scheduledStart, LocalTime scheduledEnd,
                              Duration scheduledBreak) implements WorkingTimeSystem {

    /** 労基法 34 条。6 時間を<strong>超える</strong>なら 45 分、8 時間を超えるなら 60 分。 */
    private static final Duration SIX_HOURS = Duration.ofHours(6);
    private static final Duration EIGHT_HOURS = Duration.ofHours(8);
    private static final Duration BREAK_OVER_SIX_HOURS = Duration.ofMinutes(45);
    private static final Duration BREAK_OVER_EIGHT_HOURS = Duration.ofMinutes(60);

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
        // ★ 8 時間超の 60 分も、この型で検査する。
        //   「所定 <= 8 時間を別の制約で保証しているから恒真」なのは DB の話であって、
        //   Java では成り立たない。所定 <= 法定を課しているのは WorkRule であり、
        //   public record であるこの型は単体で不正な値を保持できてしまう。
        Duration requiredBreak = requiredBreakFor(working);
        if (scheduledBreak.compareTo(requiredBreak) < 0) {
            throw new BusinessRuleViolationException("BR-08",
                    "所定労働時間が %s の場合、休憩は %s 分以上必要です（労基法 34 条）: 休憩 %s"
                            .formatted(working, requiredBreak.toMinutes(), scheduledBreak));
        }
    }

    /**
     * その所定労働時間に必要な休憩（労基法 34 条）。
     *
     * <p>条文は「6 時間を<strong>超える</strong>場合」「8 時間を<strong>超える</strong>場合」なので、
     * ちょうど 6 時間・ちょうど 8 時間は下の段に入る。
     */
    private static Duration requiredBreakFor(Duration working) {
        if (working.compareTo(EIGHT_HOURS) > 0) {
            return BREAK_OVER_EIGHT_HOURS;
        }
        return working.compareTo(SIX_HOURS) > 0 ? BREAK_OVER_SIX_HOURS : Duration.ZERO;
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
