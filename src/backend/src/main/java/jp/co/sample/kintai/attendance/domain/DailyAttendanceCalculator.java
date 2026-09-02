package jp.co.sample.kintai.attendance.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jp.co.sample.kintai.shared.domain.PremiumType;
import jp.co.sample.kintai.shared.domain.TimeRange;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.FixedTimeSystem;
import jp.co.sample.kintai.workrule.domain.FlextimeSystem;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * 1 日分の労働時間を割増区分ごとに集計する。
 *
 * <p>処理は「1 本の労働区間を、割増の切り替わり点でひたすら細切れにしていく」形をとる。
 * 適用する規則は<strong>労働時間制度と勤務日の区分の両方</strong>で決まる。
 */
public class DailyAttendanceCalculator {

    private final CompanyCalendar calendar;

    public DailyAttendanceCalculator(CompanyCalendar calendar) {
        if (calendar == null) {
            throw new IllegalArgumentException("会社カレンダーに null は許されません");
        }
        this.calendar = calendar;
    }

    public DailyAttendance calculate(LocalDate workDate, TimeClockSequence punches,
                                     WorkRule workRule) {
        WorkingTimeSystemType systemType = workRule.systemType();
        DayType workDayType = calendar.dayTypeOf(workDate);

        if (punches.isEmpty()) {
            return DailyAttendance.absent(workDate, workDayType, systemType);
        }

        List<TimeRange> worked = punches.toWorkedRanges();
        List<WorkSlice> slices = worked.stream().map(WorkSlice::plain).toList();
        for (AttendanceRule rule : rulesFor(workRule, workDayType)) {
            slices = rule.apply(slices);
        }

        Duration workingTime = sum(slices, slice -> true);
        return new DailyAttendance(workDate, workDayType, systemType, slices,
                workingTime,
                breakTimeOf(worked),
                sum(slices, slice -> slice.premiums().stream()
                        .noneMatch(PremiumType::partitionsWorkingTime)),
                sum(slices, slice -> slice.has(PremiumType.OVERTIME_WITHIN_STATUTORY)),
                sum(slices, slice -> slice.has(PremiumType.OVERTIME_BEYOND_STATUTORY)),
                sum(slices, slice -> slice.has(PremiumType.NIGHT)),
                sum(slices, slice -> slice.has(PremiumType.LEGAL_HOLIDAY)));
    }

    /**
     * 適用する規則を選ぶ。<strong>この選択自体が業務判断である。</strong>
     *
     * <p>{@code default} 句を書かないので、制度を追加した瞬間にここがコンパイルエラーになる。
     */
    List<AttendanceRule> rulesFor(WorkRule workRule, DayType workDayType) {
        var boundary = new CalendarDayBoundaryRule();
        var night = new NightWorkRule(workRule.nightWindow());
        var holiday = new LegalHolidayWorkRule(calendar);

        return switch (workRule.workingTimeSystem()) {
            // 固定時間制: 日次で残業が確定する
            case FixedTimeSystem fixed -> List.of(boundary, night, holiday,
                    new DailyOvertimeRule(scheduledFor(workDayType, fixed),
                            workRule.statutoryDailyWorkingTime()));
            // フレックス: 日々の所定が無いので日次では残業を判定しない（BR-05）
            case FlextimeSystem flex -> List.of(boundary, night, holiday);
        };
    }

    /**
     * その勤務日の所定労働時間。
     *
     * <p><strong>勤務日の区分で決まる。</strong> 1 つの勤務日に所定は 1 つしかない。
     * 暦日ごとに変えると、累積の閾値が区間ごとに変わってしまい定義できない。
     * 暦日が効くのは法定休日労働の判定だけである。
     */
    private static Duration scheduledFor(DayType workDayType, FixedTimeSystem fixed) {
        return switch (workDayType) {
            case WORKDAY -> fixed.scheduledWorkingTime();
            // 所定休日・法定休日は所定労働時間 0 として扱う（BR-07）
            case NON_LEGAL_HOLIDAY, LEGAL_HOLIDAY -> Duration.ZERO;
        };
    }

    /** 休憩時間。拘束時間から実労働区間の合計を引く。 */
    private static Duration breakTimeOf(List<TimeRange> worked) {
        if (worked.isEmpty()) {
            return Duration.ZERO;
        }
        TimeRange span = new TimeRange(worked.get(0).start(),
                worked.get(worked.size() - 1).end());
        Duration workedTotal = worked.stream()
                .map(TimeRange::duration)
                .reduce(Duration.ZERO, Duration::plus);
        return span.duration().minus(workedTotal);
    }

    private static Duration sum(List<WorkSlice> slices,
                                java.util.function.Predicate<WorkSlice> filter) {
        List<WorkSlice> matched = new ArrayList<>();
        for (WorkSlice slice : slices) {
            if (filter.test(slice)) {
                matched.add(slice);
            }
        }
        return matched.stream().map(WorkSlice::duration).reduce(Duration.ZERO, Duration::plus);
    }
}
