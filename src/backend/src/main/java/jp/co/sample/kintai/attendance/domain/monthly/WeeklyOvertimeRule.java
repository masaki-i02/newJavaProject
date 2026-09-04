package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * 週 40 時間超を判定する（BR-04）。
 *
 * <p><strong>1 日 8 時間超として既に法定外残業に計上した時間を、
 * 週 40 時間超としてもう一度数えない。</strong>
 * 各日の「法定内労働時間」を求め、その週の合計が 40 時間を超えた分だけを追加の時間外とする。
 *
 * <p><strong>フレックスには適用しない</strong>（設計書 3.3）。
 * フレックスは清算期間を通じた総枠で判定するので（BR-05）、
 * 週単位の判定を重ねると同じ労働時間を 2 つの基準で二重に評価することになる。
 * その分岐は呼び出し側（月次清算）が制度の {@code switch} で行う。
 */
public final class WeeklyOvertimeRule {

    /** 週の起算曜日。法定休日を日曜としているので、週の区切りと休日の区切りが揃う。 */
    public static final DayOfWeek WEEK_START = DayOfWeek.SUNDAY;

    private final Duration statutoryWeekly;

    public WeeklyOvertimeRule(Duration statutoryWeekly) {
        if (statutoryWeekly == null) {
            throw new IllegalArgumentException("週法定労働時間に null は許されません");
        }
        if (!statutoryWeekly.isPositive()) {
            throw new IllegalArgumentException(
                    "週法定労働時間は正である必要があります: " + statutoryWeekly);
        }
        this.statutoryWeekly = statutoryWeekly;
    }

    /**
     * 週次判定に必要な日次の範囲。<strong>対象月の範囲より広い。</strong>
     *
     * <p>月初の週は前月の日を含む。前月の日が欠けると、その週の法定内労働が過少になり、
     * <strong>週 40 時間超を取りこぼす。</strong>
     * 未計算の日を検出する範囲もこれに合わせる。
     */
    public static DateRange scanRangeFor(DateRange settlementPeriod) {
        return new DateRange(
                settlementPeriod.from().with(TemporalAdjusters.previousOrSame(WEEK_START)),
                settlementPeriod.toExclusive().with(TemporalAdjusters.nextOrSame(WEEK_START)));
    }

    /**
     * 日次勤怠を週ごとにまとめ、それぞれの週 40 時間超を求める。
     *
     * <p>渡す日次は {@link #scanRangeFor} の範囲で読むこと。
     * 対象月の中だけを渡すと、月初の週に必要な前月の日が欠けたまま判定してしまう。
     */
    public List<WeeklyOvertime> apply(List<DailyAttendance> days) {
        if (days == null) {
            throw new IllegalArgumentException("日次勤怠に null は許されません");
        }
        Map<LocalDate, List<DailyAttendance>> byWeek = new TreeMap<>();
        for (DailyAttendance day : days) {
            byWeek.computeIfAbsent(weekStartOf(day.workDate()), key -> new ArrayList<>())
                    .add(day);
        }
        List<WeeklyOvertime> weeks = new ArrayList<>();
        byWeek.forEach((weekStart, week) -> {
            Duration inside = week.stream()
                    .map(WeeklyOvertimeRule::statutoryInsideTime)
                    .reduce(Duration.ZERO, Duration::plus);
            Duration excess = inside.minus(statutoryWeekly);
            weeks.add(new WeeklyOvertime(weekStart, weekStart.plusWeeks(1), inside,
                    excess.isNegative() ? Duration.ZERO : excess));
        });
        return List.copyOf(weeks);
    }

    /** 対象月に計上される週 40 時間超の合計。<strong>末日が属する月で振り分ける。</strong> */
    public Duration totalChargedTo(List<WeeklyOvertime> weeks, YearMonth month) {
        return weeks.stream()
                .filter(week -> week.chargedMonth().equals(month))
                .map(WeeklyOvertime::overtimeTime)
                .reduce(Duration.ZERO, Duration::plus);
    }

    /**
     * 1 日 8 時間以内に収まっている労働時間。
     *
     * <p>法定外残業（1 日 8 時間超）を引く。<strong>これが二重計上を防ぐ。</strong>
     * 法定休日労働も引く。時間外労働に算入しないので（労基法 36 条）、
     * 週 40 時間の判定にも入れない。
     */
    static Duration statutoryInsideTime(DailyAttendance day) {
        return day.workingTime()
                .minus(day.overtimeBeyondStatutoryTime())
                .minus(day.legalHolidayTime());
    }

    private static LocalDate weekStartOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(WEEK_START));
    }
}
