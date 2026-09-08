package jp.co.sample.kintai.workrule.domain;

import java.time.Duration;
import java.time.YearMonth;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * 清算期間。<strong>暦月 ∩ 在籍期間</strong>（BR-05）。
 *
 * <p>暦月に固定してはいけない。4/15 入社の社員の初月は 16 日しかなく、
 * 30 日として計算すると<strong>法定労働時間の総枠が倍近く過大になり、
 * 時間外労働が計上されずに賃金が不足する。</strong>
 *
 * @param month  対象月
 * @param period 実際の期間。半開区間
 */
public record SettlementPeriod(YearMonth month, DateRange period) {

    public SettlementPeriod {
        if (month == null || period == null) {
            throw new IllegalArgumentException("清算期間の項目に null は許されません");
        }
        DateRange calendarMonth = calendarMonthOf(month);
        if (period.from().isBefore(calendarMonth.from())
                || period.toExclusive().isAfter(calendarMonth.toExclusive())) {
            throw new IllegalArgumentException(
                    "清算期間は対象月の内側である必要があります: %s / 対象月 %s"
                            .formatted(period, month));
        }
    }

    /** 暦月と在籍期間の交差を取る。重ならなければ、その月に清算するものは無い。 */
    public static Optional<SettlementPeriod> of(YearMonth month, DateRange employmentPeriod) {
        return calendarMonthOf(month).intersect(employmentPeriod)
                .map(intersection -> new SettlementPeriod(month, intersection));
    }

    private static DateRange calendarMonthOf(YearMonth month) {
        return DateRange.ofMonth(month);
    }

    /** 清算期間の暦日数。 */
    public long days() {
        return period.days();
    }

    /**
     * 法定労働時間の総枠（労基法 32 条の 3）。= 暦日数 ÷ 7 × 週法定労働時間。
     *
     * <p><strong>分未満は切り捨てる。</strong> 総枠が小さいほど時間外として算定される
     * 時間が増え、割増賃金が多くなる。労働者に有利な方向である。
     */
    public Duration statutoryTotalLimit(Duration statutoryWeekly) {
        if (statutoryWeekly == null) {
            throw new IllegalArgumentException("週法定労働時間に null は許されません");
        }
        if (!statutoryWeekly.isPositive()) {
            throw new IllegalArgumentException("週法定労働時間は正である必要があります: "
                    + statutoryWeekly);
        }
        // 桁あふれを黙って負の総枠にしない。総枠が負になると全時間が時間外になる
        return Duration.ofMinutes(Math.multiplyExact(days(), statutoryWeekly.toMinutes()) / 7);
    }

    /**
     * 所定総労働時間が法定総枠を超えるか。超えるなら警告を返す。
     *
     * <p>フレックスでは、所定総労働時間が総枠を超える月がありうる。
     * 2026-06 は所定 22 日 × 8 時間 = 10,560 分に対し、総枠は 30 ÷ 7 × 2,400 = 10,285 分である。
     *
     * <p><strong>例外にしない。</strong> 適法な状態なので登録は許し、人事に知らせるだけにする。
     *
     * <p><strong>呼ぶのは規則の登録・改定時だけである。</strong>
     * カレンダーの一括設定でも所定労働日数は動くが、そこでは呼ばない ─
     * 会社カレンダーは 1 つしかないので、変更は<strong>すべてのフレックス系列</strong>に
     * 効き、全系列を舐めることになる（落とし穴 131 と同じ形）。
     * その月に総枠を超えたことは<strong>月次清算に現れる</strong>
     * （所定どおり働いただけで時間外が付く。IT-SCN-10）ので、
     * 気づく経路が無くなるわけではない。
     *
     * <p>版 1.4 まで javadoc は「カレンダーの一括設定時にも呼ぶ」と書いていたが、
     * <strong>呼んでいなかった</strong>（落とし穴 87）。
     */
    public Optional<ScheduleCapacityWarning> checkCapacity(
            FlextimeSystem flex, int workdayCount, Duration statutoryWeekly) {
        if (flex == null) {
            throw new IllegalArgumentException("フレックスの規則に null は許されません");
        }
        Duration scheduled = flex.scheduledTotalWorkingTime(workdayCount);
        Duration limit = statutoryTotalLimit(statutoryWeekly);
        return scheduled.compareTo(limit) > 0
                ? Optional.of(new ScheduleCapacityWarning(month, scheduled, limit))
                : Optional.empty();
    }
}
