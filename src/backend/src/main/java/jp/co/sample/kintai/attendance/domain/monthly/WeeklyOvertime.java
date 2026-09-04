package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 1 週間の週 40 時間超（BR-04）。
 *
 * @param weekStart         週の起算日（日曜）
 * @param weekEndExclusive  週の終了日。<strong>含まない</strong>（次の日曜）
 * @param statutoryInsideTime その週の法定内労働時間の合計
 * @param overtimeTime      40 時間を超えた分。超えていなければ 0
 */
public record WeeklyOvertime(LocalDate weekStart, LocalDate weekEndExclusive,
                             Duration statutoryInsideTime, Duration overtimeTime) {

    public WeeklyOvertime {
        if (weekStart == null || weekEndExclusive == null
                || statutoryInsideTime == null || overtimeTime == null) {
            throw new IllegalArgumentException("週次時間外の項目に null は許されません");
        }
        if (!weekStart.isBefore(weekEndExclusive)) {
            throw new IllegalArgumentException(
                    "週の開始は終了より前である必要があります: [%s, %s)"
                            .formatted(weekStart, weekEndExclusive));
        }
        if (statutoryInsideTime.isNegative() || overtimeTime.isNegative()) {
            throw new IllegalArgumentException(
                    "労働時間を負にはできません: 法定内 %s / 時間外 %s"
                            .formatted(statutoryInsideTime, overtimeTime));
        }
        // 超過分が法定内労働そのものを上回ることはありえない
        if (overtimeTime.compareTo(statutoryInsideTime) > 0) {
            throw new IllegalArgumentException(
                    "週の時間外が法定内労働を超えています: 時間外 %s / 法定内 %s"
                            .formatted(overtimeTime, statutoryInsideTime));
        }
    }

    /**
     * その週の時間外を計上する月。
     *
     * <p><strong>末日が属する月に計上する</strong>（設計書 3.2）。
     * 週の労働時間が確定するのは末日であり、それ以前に時間外を確定できない。
     * 日数按分にすると端数処理が要り、給与計算側との突合が難しくなる。
     */
    public java.time.YearMonth chargedMonth() {
        return java.time.YearMonth.from(weekEndExclusive.minusDays(1));
    }

    public boolean hasOvertime() {
        return overtimeTime.isPositive();
    }
}
