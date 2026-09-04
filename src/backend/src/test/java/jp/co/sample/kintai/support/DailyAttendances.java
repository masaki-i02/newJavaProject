package jp.co.sample.kintai.support;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.WorkSlice;
import jp.co.sample.kintai.shared.domain.PremiumType;
import jp.co.sample.kintai.shared.domain.TimeRange;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * 月次のテスト用に日次勤怠を組み立てる。
 *
 * <p><strong>本番の計算を通して作る。</strong>
 * 集計値を手で書いた {@code DailyAttendance} を渡すと、
 * 「内訳の合計 = 実労働時間」という不変条件を自分で満たしにいくことになり、
 * 月次のテストが日次の計算とは無関係な値の上で回る。
 *
 * <p>ここが作るのは「9:00 から指定の時間だけ働いた 1 日」という<strong>事実</strong>だけで、
 * 所定 8 時間・法定 8 時間の固定時間制における区分けは
 * {@code DailyAttendanceCalculator} と同じ規則をここでも解いている。
 * 区分けそのものを検査したいテストは日次側（UT-ATT）にある。
 */
public final class DailyAttendances {

    private static final Duration SCHEDULED = Duration.ofHours(8);
    private static final Duration STATUTORY = Duration.ofHours(8);

    private DailyAttendances() {
    }

    /** 所定労働日に 9:00 から {@code worked} だけ働いた日（固定時間制）。 */
    public static DailyAttendance fixedDay(LocalDate workDate, Duration worked) {
        return day(workDate, worked, DayType.WORKDAY, WorkingTimeSystemType.FIXED);
    }

    /** フレックスの所定労働日。<strong>日次では残業を付けない</strong>（BR-05）。 */
    public static DailyAttendance flexDay(LocalDate workDate, Duration worked) {
        return day(workDate, worked, DayType.WORKDAY, WorkingTimeSystemType.FLEX);
    }

    /** 法定休日に働いた日。全時間が法定休日労働になる。 */
    public static DailyAttendance legalHolidayDay(LocalDate workDate, Duration worked) {
        return day(workDate, worked, DayType.LEGAL_HOLIDAY, WorkingTimeSystemType.FIXED);
    }

    /** 打刻の無い日（欠勤）。 */
    public static DailyAttendance absent(LocalDate workDate) {
        return DailyAttendance.absent(workDate, DayType.WORKDAY, WorkingTimeSystemType.FIXED);
    }

    /** {@code from} から {@code days} 日ぶん、毎日 {@code worked} だけ働いた日を並べる。 */
    public static List<DailyAttendance> week(LocalDate from, int days, Duration worked) {
        List<DailyAttendance> result = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            result.add(fixedDay(from.plusDays(i), worked));
        }
        return List.copyOf(result);
    }

    private static DailyAttendance day(LocalDate workDate, Duration worked, DayType dayType,
                                       WorkingTimeSystemType system) {
        if (worked.isZero()) {
            return DailyAttendance.absent(workDate, dayType, system);
        }
        // 9:00 開始。深夜帯（22:00–）に入らない範囲で使う想定
        LocalDateTime start = workDate.atTime(9, 0);
        List<WorkSlice> slices = new ArrayList<>();
        Duration base = Duration.ZERO;
        Duration within = Duration.ZERO;
        Duration beyond = Duration.ZERO;
        Duration legalHoliday = Duration.ZERO;

        if (dayType == DayType.LEGAL_HOLIDAY) {
            slices.add(new WorkSlice(new TimeRange(start, start.plus(worked)),
                    Set.of(PremiumType.LEGAL_HOLIDAY)));
            legalHoliday = worked;
        } else if (system == WorkingTimeSystemType.FLEX) {
            // フレックスは日次で残業を判定しない
            slices.add(WorkSlice.plain(new TimeRange(start, start.plus(worked))));
            base = worked;
        } else {
            base = min(worked, SCHEDULED);
            within = min(worked.minus(base), STATUTORY.minus(SCHEDULED));
            beyond = worked.minus(base).minus(within);
            LocalDateTime cursor = start;
            cursor = addSlice(slices, cursor, base, Set.of());
            cursor = addSlice(slices, cursor, within,
                    Set.of(PremiumType.OVERTIME_WITHIN_STATUTORY));
            addSlice(slices, cursor, beyond, Set.of(PremiumType.OVERTIME_BEYOND_STATUTORY));
        }

        return new DailyAttendance(workDate, dayType, system, slices,
                worked, Duration.ZERO, base, within, beyond, Duration.ZERO, legalHoliday);
    }

    private static LocalDateTime addSlice(List<WorkSlice> slices, LocalDateTime from,
                                          Duration length, Set<PremiumType> premiums) {
        if (!length.isPositive()) {
            return from;
        }
        LocalDateTime to = from.plus(length);
        slices.add(new WorkSlice(new TimeRange(from, to), premiums));
        return to;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
