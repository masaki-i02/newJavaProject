package jp.co.sample.kintai.attendance.domain.monthly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.support.DailyAttendances;

/**
 * 週 40 時間超の判定（UT-BR04-01〜07）。
 *
 * <p>この規則の主眼は<strong>二重計上を避けること</strong>である。
 * 1 日 8 時間超として既に法定外残業に計上した時間を、
 * 週 40 時間超としてもう一度数えてはならない。
 */
@DisplayName("週 40 時間超（BR-04）")
class WeeklyOvertimeRuleTest {

    private static final Duration FORTY_HOURS = Duration.ofHours(40);
    private final WeeklyOvertimeRule rule = new WeeklyOvertimeRule(FORTY_HOURS);

    /** 2026-04-05 は日曜。この週は 4/5(日)〜4/11(土)。 */
    private static final LocalDate SUN = LocalDate.of(2026, 4, 5);

    @Nested
    @DisplayName("週の合計")
    class WeeklyTotal {

        @Test
        @DisplayName("UT-BR04-01 週の法定内労働が 40 時間以内なら週 40 時間超は 0")
        void withinForty() {
            // 月〜金に 7 時間ずつ = 35 時間
            var week = DailyAttendances.week(SUN.plusDays(1), 5, Duration.ofHours(7));

            var weeks = rule.apply(week);

            assertThat(weeks).hasSize(1);
            assertThat(weeks.get(0).statutoryInsideTime()).isEqualTo(Duration.ofHours(35));
            assertThat(weeks.get(0).overtimeTime()).isZero();
            assertThat(weeks.get(0).hasOvertime()).isFalse();
        }

        @Test
        @DisplayName("UT-BR04-02 週の法定内労働が 42 時間なら 2 時間が週 40 時間超")
        void overForty() {
            // 月〜土に 7 時間ずつ = 42 時間
            var week = DailyAttendances.week(SUN.plusDays(1), 6, Duration.ofHours(7));

            var weeks = rule.apply(week);

            assertThat(weeks.get(0).statutoryInsideTime()).isEqualTo(Duration.ofHours(42));
            assertThat(weeks.get(0).overtimeTime()).isEqualTo(Duration.ofHours(2));
        }

        /**
         * <strong>この 1 件が二重計上を検出する。</strong>
         * 1 日 10 時間 × 4 日は実労働 40 時間だが、日次で 2 時間ずつ計 8 時間を
         * 法定外残業として計上済みである。法定内労働は 32 時間しかないので、
         * 週 40 時間超は 0 でなければならない。
         */
        @Test
        @DisplayName("UT-BR04-03 1 日 10 時間 × 4 日は週 40 時間超にならない（二重計上しない）")
        void doesNotDoubleCountDailyOvertime() {
            var week = List.of(
                    DailyAttendances.fixedDay(SUN.plusDays(1), Duration.ofHours(10)),
                    DailyAttendances.fixedDay(SUN.plusDays(2), Duration.ofHours(10)),
                    DailyAttendances.fixedDay(SUN.plusDays(3), Duration.ofHours(10)),
                    DailyAttendances.fixedDay(SUN.plusDays(4), Duration.ofHours(10)));

            var weeks = rule.apply(week);

            assertThat(weeks.get(0).statutoryInsideTime())
                    .as("8 時間 × 4 日。超過分は日次で計上済み")
                    .isEqualTo(Duration.ofHours(32));
            assertThat(weeks.get(0).overtimeTime()).isZero();
        }

        @Test
        @DisplayName("UT-BR04-06 週の法定内労働がちょうど 40 時間なら 0")
        void exactlyForty() {
            var week = DailyAttendances.week(SUN.plusDays(1), 5, Duration.ofHours(8));

            var weeks = rule.apply(week);

            assertThat(weeks.get(0).statutoryInsideTime()).isEqualTo(FORTY_HOURS);
            assertThat(weeks.get(0).overtimeTime()).isZero();
        }

        /** 法定休日労働は時間外労働に算入しない（労基法 36 条）ので、週の判定にも入れない。 */
        @Test
        @DisplayName("UT-BR04-08 法定休日労働は週 40 時間の判定に入れない")
        void legalHolidayIsExcluded() {
            var week = List.of(
                    DailyAttendances.fixedDay(SUN.plusDays(1), Duration.ofHours(8)),
                    DailyAttendances.fixedDay(SUN.plusDays(2), Duration.ofHours(8)),
                    DailyAttendances.fixedDay(SUN.plusDays(3), Duration.ofHours(8)),
                    DailyAttendances.fixedDay(SUN.plusDays(4), Duration.ofHours(8)),
                    DailyAttendances.fixedDay(SUN.plusDays(5), Duration.ofHours(8)),
                    // 日曜（法定休日）に 8 時間
                    DailyAttendances.legalHolidayDay(SUN, Duration.ofHours(8)));

            var weeks = rule.apply(week);

            assertThat(weeks.get(0).statutoryInsideTime())
                    .as("法定休日の 8 時間は含まない").isEqualTo(FORTY_HOURS);
            assertThat(weeks.get(0).overtimeTime()).isZero();
        }
    }

    @Nested
    @DisplayName("週の区切り")
    class WeekBoundary {

        /**
         * <strong>末日が属する月に計上する</strong>（設計書 3.2）。
         * 週の労働時間が確定するのは末日であり、それ以前に時間外を確定できない。
         */
        @Test
        @DisplayName("UT-BR04-04 月をまたぐ週は末日が属する月に計上される")
        void chargedToTheMonthOfTheLastDay() {
            // 2026-04-26(日) 〜 2026-05-02(土) の週。末日は 5 月
            var weekStart = LocalDate.of(2026, 4, 26);
            var week = DailyAttendances.week(weekStart.plusDays(1), 6, Duration.ofHours(7));

            var weeks = rule.apply(week);

            assertThat(weeks.get(0).chargedMonth()).isEqualTo(YearMonth.of(2026, 5));
            assertThat(rule.totalChargedTo(weeks, YearMonth.of(2026, 5)))
                    .isEqualTo(Duration.ofHours(2));
            assertThat(rule.totalChargedTo(weeks, YearMonth.of(2026, 4)))
                    .as("4 月には計上されない").isZero();
        }

        /**
         * <strong>月初の週は前月の日を含む。</strong>
         * 前月の日が欠けると、その週の法定内労働が過少になり週 40 時間超を取りこぼす。
         */
        @Test
        @DisplayName("UT-BR04-07 走査範囲は対象月の初日を含む週の起算日から始まる")
        void scanRangeCoversThePreviousMonth() {
            // 2026-05-01 は金曜。その週の起算日は 4/26(日)
            var may = new DateRange(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1));

            var scan = WeeklyOvertimeRule.scanRangeFor(may);

            assertThat(scan.from())
                    .as("5/1 は金曜。その週の起算日は 4/26(日)")
                    .isEqualTo(LocalDate.of(2026, 4, 26));
            // 対象月の末日 5/31 は日曜で、その週は [5/31, 6/7)。
            // 6/7 まで走査しないと、その週の月〜土が欠けて週の合計が過少になる
            assertThat(scan.toExclusive()).isEqualTo(LocalDate.of(2026, 6, 7));
            assertThat(scan.contains(LocalDate.of(2026, 6, 6)))
                    .as("末日が属する週は最後まで含む").isTrue();
        }

        @Test
        @DisplayName("UT-BR04-09 走査範囲の起算日が既に日曜ならその日から始まる")
        void scanRangeWhenAlreadySunday() {
            // 2026-03-01 は日曜
            var march = new DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));

            assertThat(WeeklyOvertimeRule.scanRangeFor(march).from())
                    .isEqualTo(LocalDate.of(2026, 3, 1));
        }

        @Test
        @DisplayName("UT-BR04-10 日曜から土曜までが 1 つの週になる")
        void weekRunsFromSundayToSaturday() {
            var week = DailyAttendances.week(SUN, 7, Duration.ofHours(1));

            var weeks = rule.apply(week);

            assertThat(weeks).hasSize(1);
            assertThat(weeks.get(0).weekStart()).isEqualTo(SUN);
            assertThat(weeks.get(0).weekEndExclusive()).isEqualTo(SUN.plusDays(7));
        }

        @Test
        @DisplayName("次の日曜からは別の週になる")
        void nextSundayStartsANewWeek() {
            var days = List.of(
                    DailyAttendances.fixedDay(SUN.plusDays(6), Duration.ofHours(8)),
                    DailyAttendances.fixedDay(SUN.plusDays(7), Duration.ofHours(8)));

            var weeks = rule.apply(days);

            assertThat(weeks).hasSize(2);
            assertThat(weeks.get(0).weekStart()).isEqualTo(SUN);
            assertThat(weeks.get(1).weekStart()).isEqualTo(SUN.plusDays(7));
        }
    }

    @Nested
    @DisplayName("不変条件")
    class Invariants {

        @Test
        @DisplayName("週法定労働時間が 0 の規則は作れない")
        void zeroStatutoryWeekly() {
            assertThatThrownBy(() -> new WeeklyOvertimeRule(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("週法定労働時間は正である必要があります");
        }

        @Test
        @DisplayName("時間外が法定内労働を超える値では生成できない")
        void overtimeCannotExceedInside() {
            assertThatThrownBy(() -> new WeeklyOvertime(SUN, SUN.plusDays(7),
                    Duration.ofHours(10), Duration.ofHours(11)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("週の時間外が法定内労働を超えています");
        }

        @Test
        @DisplayName("日次勤怠が 1 件も無ければ週も無い")
        void noDays() {
            assertThat(rule.apply(List.of())).isEmpty();
        }
    }
}
