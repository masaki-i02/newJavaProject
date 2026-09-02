package jp.co.sample.kintai.attendance.domain;

import java.time.Duration;

/**
 * 労基法 34 条が要求する休憩時間（BR-08）。
 *
 * <p><strong>不足していても計算は打刻どおりに行う。</strong> 警告を出すだけに留める。
 * 勝手に休憩を差し引くと、実際には働いていた時間が消えて賃金の不払いになる。
 */
public record BreakTimeRequirement(Duration workingTime, Duration actualBreak) {

    private static final Duration SIX_HOURS = Duration.ofHours(6);
    private static final Duration EIGHT_HOURS = Duration.ofHours(8);

    public BreakTimeRequirement {
        if (workingTime == null || actualBreak == null) {
            throw new IllegalArgumentException("休憩の判定に null は許されません");
        }
        // 負の労働時間・負の休憩は「休憩の要件を満たしている」と誤答する
        if (workingTime.isNegative() || actualBreak.isNegative()) {
            throw new IllegalArgumentException(
                    "労働時間と休憩時間を負にはできません: 労働 %s / 休憩 %s"
                            .formatted(workingTime, actualBreak));
        }
    }

    /** 必要な休憩時間。 */
    public Duration required() {
        if (workingTime.compareTo(EIGHT_HOURS) > 0) {
            return Duration.ofMinutes(60);
        }
        if (workingTime.compareTo(SIX_HOURS) > 0) {
            return Duration.ofMinutes(45);
        }
        return Duration.ZERO;
    }

    public boolean isSatisfied() {
        return actualBreak.compareTo(required()) >= 0;
    }
}
