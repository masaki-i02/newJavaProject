package jp.co.sample.kintai.payroll.presentation;

import java.time.LocalDate;

import jp.co.sample.kintai.workrule.domain.AnnualScheduledHours;

/**
 * 年間の所定と 1 か月平均所定労働時間数（API設計書 5）。
 *
 * @param period 数えた範囲。「年度」の解釈は会社によって違うので明示する
 */
public record AnnualScheduledHoursResponse(int fiscalYear, Period period,
                                           int scheduledDays, int monthlyAverageMinutes) {

    /** 半開区間。 */
    public record Period(LocalDate from, LocalDate toExclusive) {
    }

    static AnnualScheduledHoursResponse from(AnnualScheduledHours annual) {
        return new AnnualScheduledHoursResponse(annual.fiscalYear(),
                new Period(annual.period().from(), annual.period().toExclusive()),
                annual.scheduledDays(), (int) annual.monthlyAverage().toMinutes());
    }
}
