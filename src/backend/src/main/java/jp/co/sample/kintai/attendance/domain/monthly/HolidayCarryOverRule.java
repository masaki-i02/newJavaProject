package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.WorkSlice;
import jp.co.sample.kintai.shared.domain.PremiumType;
import jp.co.sample.kintai.workrule.domain.DayType;

/**
 * 法定休日から翌暦日へ及んだ労働を、その暦日の勤務と通算する（BR-07）。
 *
 * <p>法定休日の労働が翌日 0 時以降に及んだ部分は<strong>休日労働ではない</strong>
 * （昭 63.3.14 基発 150 号）。同じ暦日に通常のシフトがあれば通算し、
 * 8 時間を超えた分を法定外残業とする。
 *
 * <p><strong>これは BR-03（労働時間の帰属は勤務日）の例外である。</strong>
 * 勤務日を単位に日次で計算するかぎり通算できないので、月次で暦日ごとに突き合わせる。
 * 通算するのは<strong>法定休日の勤務日から持ち越された区間を受けた暦日だけ</strong>で、
 * 通常の日跨ぎ勤務は BR-03 のとおり勤務日に帰属したままにする。
 * ここで一般化すると BR-03 を壊す。
 *
 * <p><strong>日次勤怠は書き換えない。</strong>
 * 日次は打刻から確定した一次の集計であり、後から別の暦日の事情で書き換えると、
 * 日次の画面と月次の画面で同じ日の値が食い違う。
 * {@link WeeklyOvertimeRule} が日次を書き換えずに {@link WeeklyOvertime} を別に持つのと
 * 同じ理由による。
 */
public final class HolidayCarryOverRule {

    private final Duration statutoryDaily;

    public HolidayCarryOverRule(Duration statutoryDaily) {
        if (statutoryDaily == null) {
            throw new IllegalArgumentException("1 日の法定労働時間に null は許されません");
        }
        if (!statutoryDaily.isPositive()) {
            throw new IllegalArgumentException(
                    "1 日の法定労働時間は正である必要があります: " + statutoryDaily);
        }
        this.statutoryDaily = statutoryDaily;
    }

    /**
     * 通算を行う。
     *
     * @param days 週次判定と同じ走査範囲で読んだ日次勤怠。
     *             <strong>月初の暦日は前月末日（法定休日）から持ち越されうる</strong>ので、
     *             対象月の中だけを渡すと通算を取りこぼす
     * @return 持ち越しを受けた暦日ごとの通算結果。暦日の昇順
     */
    public List<HolidayCarryOver> apply(List<DailyAttendance> days) {
        if (days == null) {
            throw new IllegalArgumentException("日次勤怠に null は許されません");
        }
        Map<LocalDate, Duration> carriedByDate = carriedInto(days);
        if (carriedByDate.isEmpty()) {
            return List.of();
        }

        List<HolidayCarryOver> result = new ArrayList<>();
        carriedByDate.forEach((date, carried) -> {
            Duration calendarDayTime = sumOn(days, date,
                    slice -> !slice.has(PremiumType.LEGAL_HOLIDAY));
            Duration alreadyBeyond = sumOn(days, date,
                    slice -> slice.has(PremiumType.OVERTIME_BEYOND_STATUTORY));

            // ★ 既に法定外残業として計上済みの分を引く。これが二重計上を防ぐ要である
            Duration beyond = floorAtZero(calendarDayTime.minus(statutoryDaily));
            Duration additional = floorAtZero(beyond.minus(alreadyBeyond));

            result.add(new HolidayCarryOver(date, carried, calendarDayTime,
                    alreadyBeyond, additional));
        });
        return List.copyOf(result);
    }

    /** 通算で新たに生じた法定外残業の合計。 */
    public static Duration totalAdditionalOvertime(List<HolidayCarryOver> carryOvers) {
        return carryOvers.stream()
                .map(HolidayCarryOver::additionalOvertime)
                .reduce(Duration.ZERO, Duration::plus);
    }

    /**
     * 法定休日の勤務日から翌暦日へ持ち越された労働時間を暦日ごとに集める。
     *
     * <p>持ち越しの条件は 2 つある。
     * <strong>勤務日が法定休日であること</strong>と、
     * <strong>その区間に {@code LEGAL_HOLIDAY} が付いていないこと</strong>である。
     * 後者は区間の暦日で判断されているので（{@code LegalHolidayWorkRule}）、
     * 翌暦日も法定休日なら付いたままになり、持ち越しにならない。
     */
    private static Map<LocalDate, Duration> carriedInto(List<DailyAttendance> days) {
        Map<LocalDate, Duration> carried = new TreeMap<>();
        for (DailyAttendance day : days) {
            if (day.dayType() != DayType.LEGAL_HOLIDAY) {
                continue;
            }
            for (WorkSlice slice : day.slices()) {
                if (slice.has(PremiumType.LEGAL_HOLIDAY)
                        || !slice.calendarDate().isAfter(day.workDate())) {
                    continue;
                }
                carried.merge(slice.calendarDate(), slice.duration(), Duration::plus);
            }
        }
        return carried;
    }

    private static Duration sumOn(List<DailyAttendance> days, LocalDate calendarDate,
                                  java.util.function.Predicate<WorkSlice> filter) {
        return days.stream()
                .flatMap(day -> day.slices().stream())
                .filter(slice -> slice.calendarDate().equals(calendarDate))
                .filter(filter)
                .map(WorkSlice::duration)
                .reduce(Duration.ZERO, Duration::plus);
    }

    private static Duration floorAtZero(Duration value) {
        return value.isNegative() ? Duration.ZERO : value;
    }
}
