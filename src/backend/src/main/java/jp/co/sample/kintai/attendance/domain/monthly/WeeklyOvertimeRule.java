package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
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
    /**
     * 法定休日からの通算（BR-07）を織り込んで週 40 時間超を求める。
     *
     * <p><strong>通算で法定外残業になった時間を、週の法定内労働から引く。</strong>
     * 引かないと、同じ時間を法定外残業としても週 40 時間超としても数えることになる。
     * {@link #statutoryInsideTime} が日次の法定外残業を引いているのと同じ理由である。
     *
     * <p>引く先は<strong>その時間が属する勤務日の週</strong>である。
     * 持ち越し先の暦日で引くと、法定休日を日曜以外にした瞬間に
     * 時間を数えた週と引く週がずれ、防ごうとしていた二重計上が復活する。
     *
     * @param days 週次判定の走査範囲で読んだ日次勤怠
     */
    public List<WeeklyOvertime> apply(List<DailyAttendance> days,
                                      List<HolidayCarryOver> carryOvers) {
        if (days == null || carryOvers == null) {
            throw new IllegalArgumentException("週次判定の引数に null は許されません");
        }
        Map<LocalDate, Duration> carriedByWorkDate = new TreeMap<>();
        for (HolidayCarryOver carryOver : carryOvers) {
            carryOver.additionalByWorkDate()
                    .forEach((workDate, value) ->
                            carriedByWorkDate.merge(workDate, value, Duration::plus));
        }

        Map<LocalDate, List<DailyAttendance>> byWeek = new TreeMap<>();
        for (DailyAttendance day : days) {
            byWeek.computeIfAbsent(weekStartOf(day.workDate()), key -> new ArrayList<>())
                    .add(day);
        }

        List<WeeklyOvertime> weeks = new ArrayList<>();
        byWeek.forEach((weekStart, week) ->
                weeks.add(weekOf(weekStart, week, carriedByWorkDate)));
        return List.copyOf(weeks);
    }

    /**
     * 1 週間ぶんを組み立てる。
     *
     * <p><strong>超過が発生した日を特定する。</strong>
     * 週の法定内労働を日付順に積み、40 時間を超えた部分がどの日のものかで振り分ける。
     * 週の合計だけを持って「末日の属する月」に計上すると、
     * 月末が金曜の月に退職した社員の最終週が<strong>どの月にも計上されなくなる</strong>。
     */
    private WeeklyOvertime weekOf(LocalDate weekStart, List<DailyAttendance> week,
                                  Map<LocalDate, Duration> carriedByWorkDate) {
        List<DailyAttendance> ordered = week.stream()
                .sorted(Comparator.comparing(DailyAttendance::workDate))
                .toList();

        Duration inside = Duration.ZERO;
        Duration accumulated = Duration.ZERO;
        Map<YearMonth, Duration> overtimeByMonth = new TreeMap<>();

        for (DailyAttendance day : ordered) {
            Duration dayInside = floorAtZero(statutoryInsideTime(day)
                    .minus(carriedByWorkDate.getOrDefault(day.workDate(), Duration.ZERO)));
            inside = inside.plus(dayInside);

            Duration excess = excessPartOf(accumulated, dayInside);
            if (excess.isPositive()) {
                overtimeByMonth.merge(YearMonth.from(day.workDate()), excess, Duration::plus);
            }
            accumulated = accumulated.plus(dayInside);
        }
        return new WeeklyOvertime(weekStart, weekStart.plusWeeks(1), inside,
                floorAtZero(inside.minus(statutoryWeekly)), overtimeByMonth);
    }

    /**
     * 累積が {@code accumulated} の位置にある長さ {@code length} の労働のうち、
     * 週法定労働時間を超えている部分の長さ。
     */
    private Duration excessPartOf(Duration accumulated, Duration length) {
        Duration end = accumulated.plus(length);
        if (end.compareTo(statutoryWeekly) <= 0) {
            return Duration.ZERO;
        }
        Duration excessStart = accumulated.compareTo(statutoryWeekly) >= 0
                ? accumulated
                : statutoryWeekly;
        return end.minus(excessStart);
    }

    static Duration floorAtZero(Duration value) {
        return value.isNegative() ? Duration.ZERO : value;
    }

    /**
     * 対象月に計上される週 40 時間超の合計。
     *
     * <p><strong>超過が発生した暦日の属する月で振り分ける。</strong>
     * 週の合計を末日の月へ寄せると、7/26(日)〜8/1(土) の週に 7/26〜7/31 だけ働いて
     * 7/31 に退職した社員の超過が 8 月に計上され、
     * 8 月の清算は行われないので<strong>誰にも計上されなくなる</strong>。
     */
    public Duration totalChargedTo(List<WeeklyOvertime> weeks, YearMonth month) {
        return weeks.stream()
                .map(week -> week.overtimeChargedTo(month))
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
