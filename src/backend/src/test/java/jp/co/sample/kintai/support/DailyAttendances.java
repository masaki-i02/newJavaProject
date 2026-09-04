package jp.co.sample.kintai.support;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceCalculator;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockSequence;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystem;

/**
 * 月次のテスト用に日次勤怠を組み立てる。
 *
 * <p><strong>本番の {@link DailyAttendanceCalculator} を通して作る。</strong>
 * 集計値を手で書いた {@code DailyAttendance} を渡すと、
 * 「どの区間に法定外残業や法定休日労働が付くか」を代役が決めることになり、
 * 月次のテストが<strong>日次の計算を 1 行も検査しない</strong>ものになる
 * （CLAUDE.md 落とし穴 37）。
 *
 * <p>ここが作るのは「9:00 から指定の時間だけ働いた 1 日」という<strong>事実</strong>だけで、
 * 区分けは本番の規則が決める。勤務日の区分は渡された会社カレンダーが決めるので、
 * 法定休日として扱いたい日はカレンダーに登録しておくこと。
 */
public final class DailyAttendances {

    /**
     * 1 日に入れられる労働時間の上限。
     *
     * <p><strong>実在しない日次を作らせないための歯止め。</strong>
     * 「1 区間 177 時間の 1 日」を作れると、暦日境界も深夜帯もまたがない区間ができ、
     * 本番では決して現れない形の上でテストが回る。
     */
    private static final Duration MAX_PER_DAY = Duration.ofHours(24);

    private final CompanyCalendar calendar;
    private final DailyAttendanceCalculator calculator;

    public DailyAttendances(CompanyCalendar calendar) {
        this.calendar = calendar;
        this.calculator = new DailyAttendanceCalculator(calendar);
    }

    /** 所定労働日に 9:00 から {@code worked} だけ働いた日（固定時間制）。 */
    public DailyAttendance fixedDay(LocalDate workDate, Duration worked) {
        return day(workDate, worked, WorkRules.fixed());
    }

    /** フレックスの 1 日。<strong>日次では残業を付けない</strong>（BR-05）。 */
    public DailyAttendance flexDay(LocalDate workDate, Duration worked) {
        return day(workDate, worked, WorkRules.flex());
    }

    /**
     * 法定休日に働いた日。
     *
     * <p><strong>カレンダーに法定休日として登録されていること</strong>が前提である。
     * 登録されていないと本番の規則が法定休日労働と判定せず、テストの前提が崩れる。
     */
    public DailyAttendance legalHolidayDay(LocalDate workDate, Duration worked) {
        if (calendar.dayTypeOf(workDate) != DayType.LEGAL_HOLIDAY) {
            throw new IllegalArgumentException(
                    "法定休日としてカレンダーに登録されていません: " + workDate);
        }
        return day(workDate, worked, WorkRules.fixed());
    }

    /**
     * 22:00 をまたいで働き、末尾 {@code night} が深夜帯に入る 1 日（フレックス）。
     *
     * <p><strong>深夜帯の中に区間が来るように出勤時刻を逆算する。</strong>
     * 開始を固定して末尾に深夜の印を付けるだけだと、17:00–19:00 のような
     * <strong>深夜帯の外の区間</strong>に深夜が付いた日次ができ、
     * 深夜帯の定義とは無関係な値の上でテストが回る。
     */
    public DailyAttendance flexNightDay(LocalDate workDate, Duration worked, Duration night) {
        if (night.compareTo(worked) > 0) {
            throw new IllegalArgumentException("深夜が実労働を超えています");
        }
        // 22:00 に深夜が始まるので、そこから night だけ働いて終わるように出勤を逆算する
        LocalDateTime end = workDate.atTime(22, 0).plus(night);
        return day(workDate, end.minus(worked), worked, WorkRules.flex());
    }

    /** 打刻の無い日（欠勤）。 */
    public DailyAttendance absent(LocalDate workDate) {
        return calculator.calculate(workDate, TimeClockSequence.of(List.of()),
                ruleOf(WorkRules.fixed()));
    }

    /** {@code from} から {@code days} 日ぶん、毎日 {@code worked} だけ働いた日を並べる。 */
    public List<DailyAttendance> week(LocalDate from, int days, Duration worked) {
        List<DailyAttendance> result = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            result.add(fixedDay(from.plusDays(i), worked));
        }
        return List.copyOf(result);
    }

    /**
     * 合計が {@code total} になるまで、所定労働日に {@code perDay} ずつ働いた日を並べる。
     *
     * <p><strong>1 日に押し込まない。</strong>
     * 総枠（月 177 時間）に届く実労働を作るには複数日が要る。
     * 端数は最後の 1 日で調整する。
     */
    public List<DailyAttendance> flexDaysTotalling(LocalDate from, Duration total,
                                                   Duration perDay) {
        List<DailyAttendance> result = new ArrayList<>();
        Duration remaining = total;
        LocalDate date = from;
        while (remaining.isPositive()) {
            if (calendar.dayTypeOf(date) == DayType.WORKDAY) {
                Duration worked = remaining.compareTo(perDay) <= 0 ? remaining : perDay;
                result.add(flexDay(date, worked));
                remaining = remaining.minus(worked);
            }
            date = date.plusDays(1);
        }
        return List.copyOf(result);
    }

    private DailyAttendance day(LocalDate workDate, Duration worked,
                                WorkingTimeSystem system) {
        return day(workDate, workDate.atTime(9, 0), worked, system);
    }

    private DailyAttendance day(LocalDate workDate, LocalDateTime clockIn, Duration worked,
                                WorkingTimeSystem system) {
        if (worked.compareTo(MAX_PER_DAY) > 0) {
            throw new IllegalArgumentException(
                    "1 日の労働時間が %s を超えています: %s".formatted(MAX_PER_DAY, worked));
        }
        if (worked.isZero()) {
            return calculator.calculate(workDate, TimeClockSequence.of(List.of()),
                    ruleOf(system));
        }
        var punches = TimeClockSequence.of(List.of(
                new TimeClockEvent.ClockIn(clockIn),
                new TimeClockEvent.ClockOut(clockIn.plus(worked))));
        return calculator.calculate(workDate, punches, ruleOf(system));
    }

    private static WorkRule ruleOf(WorkingTimeSystem system) {
        return WorkRules.rule(system, Duration.ofHours(8), NightWindow.STANDARD);
    }
}
