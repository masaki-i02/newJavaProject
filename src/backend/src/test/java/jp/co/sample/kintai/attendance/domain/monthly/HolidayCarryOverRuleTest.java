package jp.co.sample.kintai.attendance.domain.monthly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceCalculator;
import jp.co.sample.kintai.attendance.domain.TimeClockSequence;
import jp.co.sample.kintai.support.Punches;
import jp.co.sample.kintai.support.TestCalendar;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystem;

/**
 * 法定休日から翌暦日への通算（UT-BR07-01〜04）。
 *
 * <p><strong>日次勤怠は本番の {@link DailyAttendanceCalculator} を通して作る。</strong>
 * この規則が読むのは「どの区間に {@code LEGAL_HOLIDAY} が付いているか」
 * 「どの区間が既に法定外残業になっているか」であり、
 * それを決めているのは日次側である。手で組み立てた日次を渡すと、
 * 通算の入口にあたる分類そのものを検査しないテストになる。
 */
@DisplayName("法定休日から翌暦日への通算（BR-07）")
class HolidayCarryOverRuleTest {

    /** 2026-05-03 は日曜（法定休日）、5-04 は月曜（所定労働日）。 */
    private static final LocalDate SUNDAY = LocalDate.of(2026, 5, 3);
    private static final LocalDate MONDAY = LocalDate.of(2026, 5, 4);

    private final TestCalendar calendar = TestCalendar.allWorkdays().legalHoliday(SUNDAY);
    private final DailyAttendanceCalculator daily = new DailyAttendanceCalculator(calendar);
    private final HolidayCarryOverRule rule =
            new HolidayCarryOverRule(Duration.ofHours(8));

    private DailyAttendance day(LocalDate workDate, TimeClockSequence punches,
                                WorkingTimeSystem system) {
        WorkRule workRule = WorkRules.rule(system);
        return daily.calculate(workDate, punches, workRule);
    }

    /** 日曜（法定休日）22:00 → 月曜 06:00。0 時以降の 6 時間が持ち越される。 */
    private DailyAttendance holidayNightShift(WorkingTimeSystem system) {
        return day(SUNDAY, Punches.on("2026-05-03").in("22:00").out("2026-05-04T06:00")
                .build(), system);
    }

    /** 月曜の通常シフト 9:00–18:00（休憩 1 時間）= 実労働 8 時間。 */
    private DailyAttendance mondayShift(WorkingTimeSystem system) {
        return day(MONDAY, Punches.on("2026-05-04").in("09:00")
                .breakFrom("12:00").breakTo("13:00").out("18:00").build(), system);
    }

    @Test
    @DisplayName("UT-BR07-01 法定休日から持ち越した 6 時間と通常シフト 8 時間を通算する")
    void carriesOverIntoTheNextCalendarDay() {
        var days = List.of(holidayNightShift(WorkRules.fixed()),
                mondayShift(WorkRules.fixed()));

        List<HolidayCarryOver> carryOvers = rule.apply(days);

        assertThat(carryOvers).hasSize(1);
        HolidayCarryOver monday = carryOvers.getFirst();
        assertThat(monday.calendarDate()).isEqualTo(MONDAY);
        assertThat(monday.carriedTime())
                .as("日曜の勤務のうち 0 時以降の 6 時間").isEqualTo(Duration.ofHours(6));
        assertThat(monday.calendarDayTime())
                .as("持ち越し 6 時間 + 通常シフト 8 時間").isEqualTo(Duration.ofHours(14));
        assertThat(monday.alreadyBeyondTime())
                .as("日次ではどちらの勤務日も 8 時間を超えていない").isZero();
        assertThat(monday.additionalOvertime())
                .as("通算しないと 6 時間ぶんの 25% が支払われない")
                .isEqualTo(Duration.ofHours(6));
    }

    @Test
    @DisplayName("UT-BR07-02 翌暦日に他の勤務が無ければ、8 時間以内なので追加は 0")
    void noOtherWorkOnThatCalendarDay() {
        var days = List.of(holidayNightShift(WorkRules.fixed()));

        List<HolidayCarryOver> carryOvers = rule.apply(days);

        assertThat(carryOvers).hasSize(1);
        assertThat(carryOvers.getFirst().calendarDayTime()).isEqualTo(Duration.ofHours(6));
        assertThat(carryOvers.getFirst().additionalOvertime()).isZero();
    }

    /**
     * <strong>日次で法定外残業になっている分を引く。</strong>
     * 引かないと、同じ時間を日次の法定外残業としても通算分としても数える。
     */
    @Test
    @DisplayName("既に法定外残業に計上済みの時間を通算で二重に数えない")
    void doesNotDoubleCountAlreadyBeyondTime() {
        // 月曜 9:00–20:00（休憩 1 時間）= 実労働 10 時間 → 日次で 2 時間が法定外残業
        var longMonday = day(MONDAY, Punches.on("2026-05-04").in("09:00")
                .breakFrom("12:00").breakTo("13:00").out("20:00").build(), WorkRules.fixed());
        var days = List.of(holidayNightShift(WorkRules.fixed()), longMonday);

        HolidayCarryOver monday = rule.apply(days).getFirst();

        assertThat(monday.calendarDayTime()).isEqualTo(Duration.ofHours(16));
        assertThat(monday.alreadyBeyondTime()).isEqualTo(Duration.ofHours(2));
        assertThat(monday.additionalOvertime())
                .as("暦日の 8 時間超は 8 時間。うち 2 時間は計上済み")
                .isEqualTo(Duration.ofHours(6));
    }

    /**
     * <strong>通常の日跨ぎ勤務は通算しない。</strong>
     * 労働時間の帰属は勤務日である（BR-03）。ここで一般化すると BR-03 を壊す。
     */
    @Test
    @DisplayName("UT-BR07-04 法定休日をまたがない日跨ぎ勤務は通算の対象にならない")
    void ordinaryOvernightShiftIsNotCarriedOver() {
        // 月曜 22:00 → 火曜 06:00。勤務日は月曜（所定労働日）
        var overnight = day(MONDAY, Punches.on("2026-05-04").in("22:00")
                .out("2026-05-05T06:00").build(), WorkRules.fixed());
        var tuesday = day(LocalDate.of(2026, 5, 5), Punches.on("2026-05-05").in("09:00")
                .breakFrom("12:00").breakTo("13:00").out("18:00").build(), WorkRules.fixed());

        assertThat(rule.apply(List.of(overnight, tuesday))).isEmpty();
    }

    /** 翌日も法定休日なら持ち越しにならない。区間は暦日で判断されている。 */
    @Test
    @DisplayName("翌暦日も法定休日なら、その区間は休日労働のままで通算しない")
    void nextCalendarDayIsAlsoALegalHoliday() {
        calendar.legalHoliday(MONDAY);
        var days = List.of(holidayNightShift(WorkRules.fixed()));

        assertThat(rule.apply(days)).isEmpty();
    }
}
