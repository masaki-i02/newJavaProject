package jp.co.sample.kintai.workrule.domain;

import java.time.Duration;
import java.time.YearMonth;

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
    public static java.util.Optional<SettlementPeriod> of(YearMonth month,
                                                          DateRange employmentPeriod) {
        return calendarMonthOf(month).intersect(employmentPeriod)
                .map(intersection -> new SettlementPeriod(month, intersection));
    }

    private static DateRange calendarMonthOf(YearMonth month) {
        return new DateRange(month.atDay(1), month.plusMonths(1).atDay(1));
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
        return Duration.ofMinutes(days() * statutoryWeekly.toMinutes() / 7);
    }
}
