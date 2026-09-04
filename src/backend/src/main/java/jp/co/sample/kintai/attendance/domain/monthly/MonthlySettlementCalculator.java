package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.FixedTimeSystem;
import jp.co.sample.kintai.workrule.domain.FlextimeSystem;
import jp.co.sample.kintai.workrule.domain.SettlementPeriod;
import jp.co.sample.kintai.workrule.domain.WorkRule;

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
        requireOnePerWorkDate(days);
        requireInsideScanRange(days, period);

        // 集計は清算期間の中の日だけで行う。走査範囲の外側（前月・翌月）は週の判定にしか使わない
        List<DailyAttendance> inPeriod = days.stream()
                .filter(day -> period.period().contains(day.workDate()))
                .toList();

        Duration workingTime = sum(inPeriod, DailyAttendance::workingTime);
        Duration legalHolidayTime = sum(inPeriod, DailyAttendance::legalHolidayTime);
        Duration nightTime = sum(inPeriod, DailyAttendance::nightTime);
        Duration targetWorkingTime = workingTime.minus(legalHolidayTime);

        Duration statutoryTotalLimit =
                period.statutoryTotalLimit(workRule.statutoryWeeklyWorkingTime());

        // ★ 制度で変わるものを 1 か所の switch にまとめる。
        //   時間外だけを分岐して週の内訳を分岐し忘れると、
        //   「フレックスに週次の時間外は無い」と主張しながら週の内訳には
        //   時間外が入った状態を作れてしまう（CLAUDE.md 落とし穴 22）
        Overtime overtime = switch (workRule.workingTimeSystem()) {
            case FixedTimeSystem ignored -> fixedOvertime(inPeriod, days, period, workRule);
            case FlextimeSystem ignored ->
                    flexOvertime(targetWorkingTime, statutoryTotalLimit);
        };

        Duration scheduledTotalTime = scheduledTotalOf(workRule, period);
        Duration shortage = shortageOf(workRule, inPeriod, targetWorkingTime,
                scheduledTotalTime);

        return new MonthlySettlement(employeeId, period, workRule.seriesId(),
                workRule.systemType(),
                workingTime, legalHolidayTime, targetWorkingTime,
                scheduledTotalTime, statutoryTotalLimit,
                overtime.daily(), overtime.weekly(), overtime.carriedOver(),
                overtime.total(),
                shortage,
                nightTime, overtime.weeks(),
                AgreementUsage.of(overtime.total(), legalHolidayTime, annualUsedBefore));
    }

    /**
     * 固定時間制の時間外労働。
     *
     * <p>日次で確定した法定外残業・週 40 時間超・法定休日からの通算の合計。
     * 週次分は<strong>末日が対象月に属する週だけ</strong>を計上する。
     *
     * <p>通算 → 週次 の順で解く。
     * 通算で法定外になった時間を週の法定内から引かないと、同じ時間を 2 度数える。
     *
     * @param inPeriod 清算期間の中の日次。集計に使う
     * @param days     走査範囲の日次。<strong>週と暦日の判定に使う</strong>
     */
    private static Overtime fixedOvertime(List<DailyAttendance> inPeriod,
                                          List<DailyAttendance> days,
                                          SettlementPeriod period, WorkRule workRule) {
        List<HolidayCarryOver> carryOvers =
                new HolidayCarryOverRule(workRule.statutoryDailyWorkingTime()).apply(days);
        var weeklyRule = new WeeklyOvertimeRule(workRule.statutoryWeeklyWorkingTime());
        List<WeeklyOvertime> weeks = weeklyRule.apply(days, carryOvers);

        Duration daily = sum(inPeriod, DailyAttendance::overtimeBeyondStatutoryTime);
        Duration weekly = weeklyRule.totalChargedTo(weeks, period.month());
        Duration carriedOver = chargedCarryOver(carryOvers, period);
        // 内訳は対象月に触れる週だけ。走査範囲は月をはみ出すので、
        // 絞らないと前月・翌月だけの週まで内訳に並ぶ
        List<WeeklyOvertimeCharge> breakdown = weeks.stream()
                .filter(week -> overlaps(week, period))
                .map(week -> WeeklyOvertimeCharge.of(week, period.month()))
                .toList();
        return new Overtime(daily, weekly, carriedOver,
                daily.plus(weekly).plus(carriedOver), breakdown);
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
        // 週の内訳も作らない。時間外 0 と主張しながら内訳に時間外が入る状態を作らないため
        return new Overtime(Duration.ZERO, Duration.ZERO, Duration.ZERO,
                floorAtZero(excess), List.of());
    }

    /**
     * 同じ勤務日の日次勤怠が 2 件以上ないことを確かめる。
     *
     * <p>{@code DailyAttendance} は<strong>誰のものかを持たない</strong>ので、
     * 2 人ぶんを混ぜて渡されても型では止められない。
     * せめて重複だけは検出する。素通りさせると労働時間が二重に合計され、
     * <strong>実在しない残業に割増が付く。</strong>
     */
    private static void requireOnePerWorkDate(List<DailyAttendance> days) {
        Set<LocalDate> seen = new HashSet<>();
        for (DailyAttendance day : days) {
            if (!seen.add(day.workDate())) {
                throw new IllegalArgumentException(
                        "同じ勤務日の日次勤怠が 2 件以上あります: " + day.workDate());
            }
        }
    }

    /**
     * 渡された日次が走査範囲に収まっていることを確かめる。
     *
     * <p>範囲より<strong>広い</strong>と、週の判定に対象外の週が混じる。
     * 範囲より狭い（＝月初の週の前月分が欠けている）ことは、
     * <strong>「打刻が無い日」と区別がつかないので検出できない。</strong>
     * 呼び出し側が {@link WeeklyOvertimeRule#scanRangeFor} で読む責務を負う。
     */
    private static void requireInsideScanRange(List<DailyAttendance> days,
                                               SettlementPeriod period) {
        DateRange scanRange = WeeklyOvertimeRule.scanRangeFor(period.period());
        for (DailyAttendance day : days) {
            if (!scanRange.contains(day.workDate())) {
                throw new IllegalArgumentException(
                        "走査範囲の外の日次勤怠が渡されました: 勤務日 %s / 走査範囲 %s"
                                .formatted(day.workDate(), scanRange));
            }
        }
    }

    /**
     * 不足時間。<strong>制度によって数え方が違う。</strong>
     *
     * <p>フレックスは<strong>清算期間を通算</strong>して過不足を見る制度なので（労基法 32 条の 3）、
     * ある日の超過が別の日の不足を埋めるのが趣旨そのものである。
     *
     * <p>固定時間制はそうではない。<strong>所定は 1 日ごとに約束されている。</strong>
     * 通算の式を当てると、19 日を 8 時間・1 日を 12 時間働いて 1 日欠勤した月の不足が
     * 8 時間ではなく 4 時間になり、<strong>4 時間ぶんの欠勤控除が消える。</strong>
     * その 4 時間には法定外残業として 25% 割増が支払われているので、
     * 同じ時間が「割増の対象」と「欠勤の穴埋め」に二重に使われることになる。
     */
    private Duration shortageOf(WorkRule workRule, List<DailyAttendance> inPeriod,
                                Duration targetWorkingTime, Duration scheduledTotalTime) {
        return switch (workRule.workingTimeSystem()) {
            case FixedTimeSystem fixed -> {
                // 日ごとに所定で頭を打つ。超過が別の日の不足を埋めなくなる。
                // 打刻の無い所定労働日は一覧に現れないので、その日の所定がまるごと不足になる
                Duration fulfilled = inPeriod.stream()
                        .map(day -> min(day.workingTime(), scheduledOn(day, fixed)))
                        .reduce(Duration.ZERO, Duration::plus);
                yield floorAtZero(scheduledTotalTime.minus(fulfilled));
            }
            case FlextimeSystem ignored ->
                    floorAtZero(scheduledTotalTime.minus(targetWorkingTime));
        };
    }

    /** その勤務日の所定労働時間。所定休日・法定休日は 0（BR-07）。 */
    private Duration scheduledOn(DailyAttendance day, FixedTimeSystem fixed) {
        return calendar.dayTypeOf(day.workDate()) == DayType.WORKDAY
                ? fixed.scheduledWorkingTime()
                : Duration.ZERO;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
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

    /** その月の負の値を 0 に丸める。時間外・不足はどちらも負にならない。 */
    static Duration floorAtZero(Duration value) {
        return value.isNegative() ? Duration.ZERO : value;
    }

    /**
     * 時間外労働の内訳。<strong>制度によって埋まる項目が変わる。</strong>
     *
     * <p>週の内訳も同じ record に入れる。時間外の合計だけを制度で分け、
     * 内訳を分け忘れる形の欠陥を構造で防ぐ。
     */
    private record Overtime(Duration daily, Duration weekly, Duration carriedOver,
                            Duration total, List<WeeklyOvertimeCharge> weeks) {
    }

    /** その週が清算期間に 1 日でもかかっているか。 */
    private static boolean overlaps(WeeklyOvertime week, SettlementPeriod period) {
        return week.weekStart().isBefore(period.period().toExclusive())
                && week.weekEndExclusive().isAfter(period.period().from());
    }
}
