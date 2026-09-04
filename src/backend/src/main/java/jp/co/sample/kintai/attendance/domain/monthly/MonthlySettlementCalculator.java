package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.Duration;
import java.time.YearMonth;
import java.util.List;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.FixedTimeSystem;
import jp.co.sample.kintai.workrule.domain.FlextimeSystem;
import jp.co.sample.kintai.workrule.domain.SettlementPeriod;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * 月次の清算を行う（BR-04 / BR-05 / BR-12）。
 *
 * <p><strong>日次では確定しない労働時間だけをここで確定させる。</strong>
 * 深夜・法定休日労働・固定時間制の 1 日 8 時間超は日次が確定済みなので、
 * ここでは合計するだけである。
 *
 * <p>制度の分岐は {@code sealed interface} に対する網羅性検査つき {@code switch} で書く。
 * <strong>{@code default} 句を書かない</strong>ので、制度を追加した瞬間にここが
 * コンパイルエラーになる。
 */
public final class MonthlySettlementCalculator {

    private final CompanyCalendar calendar;

    public MonthlySettlementCalculator(CompanyCalendar calendar) {
        if (calendar == null) {
            throw new IllegalArgumentException("会社カレンダーに null は許されません");
        }
        this.calendar = calendar;
    }

    /**
     * 清算する。
     *
     * @param days       <strong>週次判定の走査範囲</strong>で読んだ日次勤怠
     *                   （{@link WeeklyOvertimeRule#scanRangeFor}）。
     *                   対象月の中だけを渡すと、月初の週に必要な前月の日が欠ける
     * @param workRule   その月に適用される就業規則。
     *                   月中に改定された場合は<strong>末日時点の版</strong>を渡す
     * @param annualUsedBefore 当年度の当月より前の 36 協定の累計
     */
    public MonthlySettlement calculate(EmployeeId employeeId, SettlementPeriod period,
                                       List<DailyAttendance> days, WorkRule workRule,
                                       Duration annualUsedBefore) {
        if (employeeId == null || period == null || days == null || workRule == null
                || annualUsedBefore == null) {
            throw new IllegalArgumentException("月次清算の引数に null は許されません");
        }

        // 集計は清算期間の中の日だけで行う。走査範囲の外側（前月・翌月）は週の判定にしか使わない
        List<DailyAttendance> inPeriod = days.stream()
                .filter(day -> period.period().contains(day.workDate()))
                .toList();

        Duration workingTime = sum(inPeriod, DailyAttendance::workingTime);
        Duration legalHolidayTime = sum(inPeriod, DailyAttendance::legalHolidayTime);
        Duration nightTime = sum(inPeriod, DailyAttendance::nightTime);
        Duration targetWorkingTime = workingTime.minus(legalHolidayTime);

        // ★ 通算 → 週次 → 月次の順で解く。
        //   通算で法定外になった時間を週の法定内から引かないと、同じ時間を 2 度数える
        List<HolidayCarryOver> carryOvers = carryOversFor(workRule, days);
        var weeklyRule = new WeeklyOvertimeRule(workRule.statutoryWeeklyWorkingTime());
        List<WeeklyOvertime> weeks = weeklyRule.apply(days, carryOvers);
        Duration statutoryTotalLimit =
                period.statutoryTotalLimit(workRule.statutoryWeeklyWorkingTime());

        Overtime overtime = switch (workRule.workingTimeSystem()) {
            case FixedTimeSystem fixed -> fixedOvertime(inPeriod, weeks, weeklyRule,
                    period.month(), chargedCarryOver(carryOvers, period));
            case FlextimeSystem flex -> flexOvertime(targetWorkingTime, statutoryTotalLimit);
        };

        Duration scheduledTotalTime = scheduledTotalOf(workRule, period);
        Duration shortage = scheduledTotalTime.minus(targetWorkingTime);

        return new MonthlySettlement(employeeId, period, workRule.seriesId(),
                workRule.systemType(),
                workingTime, legalHolidayTime, targetWorkingTime,
                scheduledTotalTime, statutoryTotalLimit,
                overtime.daily(), overtime.weekly(), overtime.carriedOver(),
                overtime.total(),
                shortage.isNegative() ? Duration.ZERO : shortage,
                nightTime, weeks,
                AgreementUsage.of(overtime.total(), legalHolidayTime, annualUsedBefore));
    }

    /**
     * 固定時間制の時間外労働。
     *
     * <p>日次で確定した法定外残業と、週 40 時間超の合計。
     * 週次分は<strong>末日が対象月に属する週だけ</strong>を計上する。
     */
    private static Overtime fixedOvertime(List<DailyAttendance> inPeriod,
                                          List<WeeklyOvertime> weeks,
                                          WeeklyOvertimeRule weeklyRule, YearMonth month,
                                          Duration carriedOver) {
        Duration daily = sum(inPeriod, DailyAttendance::overtimeBeyondStatutoryTime);
        Duration weekly = weeklyRule.totalChargedTo(weeks, month);
        return new Overtime(daily, weekly, carriedOver,
                daily.plus(weekly).plus(carriedOver));
    }

    /**
     * 法定休日から翌暦日への通算（BR-07）。
     *
     * <p><strong>固定時間制にだけ適用する。</strong>
     * フレックスでは持ち越した時間は既に対象労働時間に入っており、清算期間の総枠で判定される。
     * 日次の 8 時間で重ねて判定すると、同じ労働時間を 2 つの基準で二重に評価することになる
     * （週 40 時間超を適用しないのと同じ理由）。
     */
    private static List<HolidayCarryOver> carryOversFor(WorkRule workRule,
                                                        List<DailyAttendance> days) {
        return switch (workRule.workingTimeSystem()) {
            case FixedTimeSystem ignored ->
                    new HolidayCarryOverRule(workRule.statutoryDailyWorkingTime()).apply(days);
            case FlextimeSystem ignored -> List.of();
        };
    }

    /**
     * 対象月に計上する通算分。<strong>暦日が清算期間に入るものだけ。</strong>
     *
     * <p>走査範囲は月をはみ出すので（{@link WeeklyOvertimeRule#scanRangeFor}）、
     * 絞らないと前月末の法定休日から持ち越した分を当月にも計上してしまう。
     */
    private static Duration chargedCarryOver(List<HolidayCarryOver> carryOvers,
                                             SettlementPeriod period) {
        return carryOvers.stream()
                .filter(carryOver -> period.period().contains(carryOver.calendarDate()))
                .map(HolidayCarryOver::additionalOvertime)
                .reduce(Duration.ZERO, Duration::plus);
    }

    /**
     * フレックスの時間外労働。
     *
     * <p><strong>清算期間の総枠を超えた分だけ。</strong>
     * 日々 8 時間を超えても、それ自体では時間外労働にならない（BR-05）。
     * 週次の判定も重ねない。同じ労働時間を 2 つの基準で二重に評価することになる。
     */
    private static Overtime flexOvertime(Duration targetWorkingTime,
                                         Duration statutoryTotalLimit) {
        Duration excess = targetWorkingTime.minus(statutoryTotalLimit);
        return new Overtime(Duration.ZERO, Duration.ZERO, Duration.ZERO,
                excess.isNegative() ? Duration.ZERO : excess);
    }

    /**
     * 所定総労働時間。
     *
     * <p><strong>清算期間の所定労働日数で数える。</strong> 暦月ではない。
     * 4/15 入社の初月を暦月で数えると、所定総が実態の倍近くになり不足時間が水増しされる。
     */
    private Duration scheduledTotalOf(WorkRule workRule, SettlementPeriod period) {
        int workdays = calendar.workdayCountIn(period.period());
        return switch (workRule.workingTimeSystem()) {
            case FixedTimeSystem fixed ->
                    fixed.scheduledWorkingTime().multipliedBy(workdays);
            case FlextimeSystem flex -> flex.scheduledTotalWorkingTime(workdays);
        };
    }

    private static Duration sum(List<DailyAttendance> days,
                                java.util.function.Function<DailyAttendance, Duration> of) {
        return days.stream().map(of).reduce(Duration.ZERO, Duration::plus);
    }

    /** 時間外労働の内訳。制度によって埋まる項目が変わる。 */
    private record Overtime(Duration daily, Duration weekly, Duration carriedOver,
                            Duration total) {
    }
}
