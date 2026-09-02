package jp.co.sample.kintai.attendance.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import jp.co.sample.kintai.shared.domain.PremiumType;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * 1 日分の集計結果。
 *
 * <p>集計値だけでなく内訳（{@code slices}）も保持する。
 * 労務の問い合わせでは「なぜこの残業時間なのか」の提示が必須であり、
 * 集計値だけでは答えられないため。
 *
 * @param workDate            勤務日。労働時間の帰属先（BR-03）
 * @param dayType             勤務日の暦日区分。<strong>表示用。内訳の根拠にはしない</strong>
 * @param workingTimeSystem   労働時間制度の判別値
 * @param slices              内訳
 * @param workingTime         実労働時間
 * @param breakTime           休憩時間
 * @param baseTime            どの排他区分も付かない時間。FIXED は所定内、FLEX は月次へ委ねる分
 * @param overtimeWithinStatutoryTime 法定内残業
 * @param overtimeBeyondStatutoryTime 法定外残業
 * @param nightTime           深夜。<strong>他の区分と重なる。合計には数えない</strong>
 * @param legalHolidayTime    法定休日労働
 */
public record DailyAttendance(LocalDate workDate, DayType dayType,
                              WorkingTimeSystemType workingTimeSystem,
                              List<WorkSlice> slices,
                              Duration workingTime, Duration breakTime,
                              Duration baseTime,
                              Duration overtimeWithinStatutoryTime,
                              Duration overtimeBeyondStatutoryTime,
                              Duration nightTime, Duration legalHolidayTime) {

    public DailyAttendance {
        if (workDate == null || dayType == null || workingTimeSystem == null || slices == null) {
            throw new IllegalArgumentException("日次勤怠の項目に null は許されません");
        }
        slices = List.copyOf(slices);

        // ★ 排他的な 4 区分の合計は必ず実労働時間に一致する。深夜は重ね掛けなので含めない
        Duration breakdown = baseTime
                .plus(overtimeWithinStatutoryTime)
                .plus(overtimeBeyondStatutoryTime)
                .plus(legalHolidayTime);
        if (!breakdown.equals(workingTime)) {
            throw new IllegalStateException(
                    "内訳の合計が実労働時間と一致しません: 内訳 %s / 実労働 %s"
                            .formatted(breakdown, workingTime));
        }

        // ★ 集計値は内訳から再集計した値と一致しなければならない
        Duration slicedWorking = total(slices, slice -> true);
        if (!slicedWorking.equals(workingTime)) {
            throw new IllegalStateException(
                    "内訳（slices）の合計が実労働時間と一致しません: 内訳 %s / 実労働 %s"
                            .formatted(slicedWorking, workingTime));
        }
        requireMatches(slices, PremiumType.NIGHT, nightTime, "深夜");
        requireMatches(slices, PremiumType.LEGAL_HOLIDAY, legalHolidayTime, "法定休日労働");
        requireMatches(slices, PremiumType.OVERTIME_WITHIN_STATUTORY,
                overtimeWithinStatutoryTime, "法定内残業");
        requireMatches(slices, PremiumType.OVERTIME_BEYOND_STATUTORY,
                overtimeBeyondStatutoryTime, "法定外残業");

        // ★ フレックスは日次で残業を判定しない（BR-05）
        if (workingTimeSystem == WorkingTimeSystemType.FLEX
                && !(overtimeWithinStatutoryTime.isZero()
                        && overtimeBeyondStatutoryTime.isZero())) {
            throw new IllegalStateException(
                    "フレックスに日次の残業を計上しています: 法定内 %s / 法定外 %s"
                            .formatted(overtimeWithinStatutoryTime,
                                    overtimeBeyondStatutoryTime));
        }

        if (nightTime.compareTo(workingTime) > 0) {
            throw new IllegalStateException(
                    "深夜労働時間が実労働時間を超えています: 深夜 %s / 実労働 %s"
                            .formatted(nightTime, workingTime));
        }
    }

    private static void requireMatches(List<WorkSlice> slices, PremiumType premium,
                                       Duration expected, String label) {
        Duration actual = total(slices, slice -> slice.has(premium));
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "%sの集計値が内訳と一致しません: 集計 %s / 内訳 %s"
                            .formatted(label, expected, actual));
        }
    }

    private static Duration total(List<WorkSlice> slices,
                                  java.util.function.Predicate<WorkSlice> filter) {
        return slices.stream().filter(filter)
                .map(WorkSlice::duration)
                .reduce(Duration.ZERO, Duration::plus);
    }

    /** 労基法 34 条の休憩を満たしているか（BR-08）。不足しても計算は変えない。 */
    public boolean breakRequirementSatisfied() {
        return new BreakTimeRequirement(workingTime, breakTime).isSatisfied();
    }

    /** 打刻が無い日（欠勤・休日）。 */
    public static DailyAttendance absent(LocalDate workDate, DayType dayType,
                                         WorkingTimeSystemType system) {
        return new DailyAttendance(workDate, dayType, system, List.of(),
                Duration.ZERO, Duration.ZERO, Duration.ZERO,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }
}
