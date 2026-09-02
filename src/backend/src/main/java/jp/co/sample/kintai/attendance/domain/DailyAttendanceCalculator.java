package jp.co.sample.kintai.attendance.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

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
public final class DailyAttendanceCalculator {

    private final CompanyCalendar calendar;

    public DailyAttendanceCalculator(CompanyCalendar calendar) {
        if (calendar == null) {
            throw new IllegalArgumentException("会社カレンダーに null は許されません");
        }
        this.calendar = calendar;
    }

    public DailyAttendance calculate(LocalDate workDate, TimeClockSequence punches,
                                     WorkRule workRule) {
        if (workDate == null || punches == null || workRule == null) {
            throw new IllegalArgumentException("日次集計の引数に null は許されません");
        }
        requireRuleCoversWorkDate(workDate, workRule);
        WorkingTimeSystemType systemType = workRule.systemType();
        DayType workDayType = calendar.dayTypeOf(workDate);

        if (punches.isEmpty()) {
            return DailyAttendance.absent(workDate, workDayType, systemType);
        }

        requireWorkDateMatchesPunches(workDate, punches.clockedInAt().orElseThrow());

        List<TimeRange> worked = punches.toWorkedRanges();
        Optional<TimeRange> span = punches.attendanceSpan();
        if (span.isEmpty()) {
            // 出勤と退勤が同一時刻。打刻は記録されているが、働いた時間は 0 分である。
            // 集計値はすべて 0 になるので、欠勤と同じ形で返す
            return DailyAttendance.absent(workDate, workDayType, systemType);
        }
        List<WorkSlice> slices = worked.stream().map(WorkSlice::plain).toList();
        for (AttendanceRule rule : rulesFor(workRule, workDayType)) {
            slices = rule.apply(slices);
        }

        Duration workingTime = sum(slices, slice -> true);
        return new DailyAttendance(workDate, workDayType, systemType, slices,
                workingTime,
                breakTimeOf(span.orElseThrow(), worked),
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
    private List<AttendanceRule> rulesFor(WorkRule workRule, DayType workDayType) {
        var boundary = new CalendarDayBoundaryRule();
        var night = new NightWorkRule(workRule.nightWindow());
        var holiday = new LegalHolidayWorkRule(calendar);

        return switch (workRule.workingTimeSystem()) {
            // 固定時間制: 日次で残業が確定する
            case FixedTimeSystem fixed -> List.of(boundary, night, holiday,
                    new DailyOvertimeRule(scheduledFor(workDayType, fixed),
                            workRule.statutoryDailyWorkingTime()));
            // フレックス: 日々の所定が無いので日次では残業を判定しない（BR-05）
            case FlextimeSystem ignored -> List.of(boundary, night, holiday);
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

    /**
     * 勤務日が打刻と一致していることを確かめる（BR-03）。
     *
     * <p>所定労働時間は勤務日の区分から決まり、法定休日の判定は区間の暦日から決まる。
     * <strong>2 つの日付がずれると、所定だけが別の日のものになる。</strong>
     * 所定内 8 時間が「法定内残業 8 時間」に化けるといった誤りが起きる。
     */
    private static void requireWorkDateMatchesPunches(LocalDate workDate,
                                                      LocalDateTime clockedInAt) {
        LocalDate punchedOn = clockedInAt.toLocalDate();
        if (!punchedOn.equals(workDate)) {
            throw new IllegalArgumentException(
                    "勤務日と出勤打刻の日付が一致しません: 勤務日 %s / 出勤 %s"
                            .formatted(workDate, punchedOn));
        }
    }

    /**
     * 就業規則がその勤務日をカバーしていることを確かめる。
     *
     * <p>時点解決は {@code EffectiveWorkRule} の責務だが、解決の結果を取り違えて
     * 別の版を渡されると、<strong>改定前の所定・法定値で黙って計算される。</strong>
     * 勤務日と打刻の一致は確かめているのに、規則との一致だけ確かめないのは非対称である。
     */
    private static void requireRuleCoversWorkDate(LocalDate workDate, WorkRule workRule) {
        if (!workRule.validPeriod().contains(workDate)) {
            throw new IllegalArgumentException(
                    "就業規則の有効期間が勤務日を含んでいません: 勤務日 %s / 有効期間 %s"
                            .formatted(workDate, workRule.validPeriod()));
        }
    }

    /**
     * 休憩時間。拘束時間から実労働区間の合計を引く。
     *
     * <p><strong>拘束時間は実労働区間からは求められない。</strong>
     * 出勤直後や退勤直前に休憩を取ると、その区間が長さ 0 になって捨てられ、
     * 出退勤の打刻時刻が失われる。同じ勤務でも休憩の時間帯を変えるだけで
     * BR-08 の判定が反転してしまう。
     */
    private static Duration breakTimeOf(TimeRange span, List<TimeRange> worked) {
        Duration workedTotal = worked.stream()
                .map(TimeRange::duration)
                .reduce(Duration.ZERO, Duration::plus);
        return span.duration().minus(workedTotal);
    }

    private static Duration sum(List<WorkSlice> slices, Predicate<WorkSlice> filter) {
        return slices.stream()
                .filter(filter)
                .map(WorkSlice::duration)
                .reduce(Duration.ZERO, Duration::plus);
    }
}
