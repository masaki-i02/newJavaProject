package jp.co.sample.kintai.attendance.domain.monthly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.support.DailyAttendances;
import jp.co.sample.kintai.support.TestCalendar;

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

    /**
     * 日次は本番の計算を通して作る。
     * 法定休日として扱う日はカレンダーへ登録しておく（{@code days.legalHolidayDay}）。
     */
    private final TestCalendar calendar = TestCalendar.allWorkdays();
    private final DailyAttendances days = new DailyAttendances(calendar);

    /** カレンダーへ法定休日として登録してから作る。 */
    private jp.co.sample.kintai.attendance.domain.DailyAttendance legalHolidayOn(
            LocalDate date, Duration worked) {
        calendar.legalHoliday(date);
        return days.legalHolidayDay(date, worked);
    }

    /** 2026-04-05 は日曜。この週は 4/5(日)〜4/11(土)。 */
    private static final LocalDate SUN = LocalDate.of(2026, 4, 5);

    @Nested
    @DisplayName("週の合計")
    class WeeklyTotal {

        @Test
        @DisplayName("UT-BR04-01 週の法定内労働が 40 時間以内なら週 40 時間超は 0")
        void withinForty() {
            // 月〜金に 7 時間ずつ = 35 時間
            var week = days.week(SUN.plusDays(1), 5, Duration.ofHours(7));

            var weeks = rule.apply(week, List.of());

            assertThat(weeks).hasSize(1);
            assertThat(weeks.get(0).statutoryInsideTime()).isEqualTo(Duration.ofHours(35));
            assertThat(weeks.get(0).overtimeTime()).isZero();
            assertThat(weeks.get(0).hasOvertime()).isFalse();
        }

        @Test
        @DisplayName("UT-BR04-02 週の法定内労働が 42 時間なら 2 時間が週 40 時間超")
        void overForty() {
            // 月〜土に 7 時間ずつ = 42 時間
            var week = days.week(SUN.plusDays(1), 6, Duration.ofHours(7));

            var weeks = rule.apply(week, List.of());

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
                    days.fixedDay(SUN.plusDays(1), Duration.ofHours(10)),
                    days.fixedDay(SUN.plusDays(2), Duration.ofHours(10)),
                    days.fixedDay(SUN.plusDays(3), Duration.ofHours(10)),
                    days.fixedDay(SUN.plusDays(4), Duration.ofHours(10)));

            var weeks = rule.apply(week, List.of());

            assertThat(weeks.get(0).statutoryInsideTime())
                    .as("8 時間 × 4 日。超過分は日次で計上済み")
                    .isEqualTo(Duration.ofHours(32));
            assertThat(weeks.get(0).overtimeTime()).isZero();
        }

        @Test
        @DisplayName("UT-BR04-06 週の法定内労働がちょうど 40 時間なら 0")
        void exactlyForty() {
            var week = days.week(SUN.plusDays(1), 5, Duration.ofHours(8));

            var weeks = rule.apply(week, List.of());

            assertThat(weeks.get(0).statutoryInsideTime()).isEqualTo(FORTY_HOURS);
            assertThat(weeks.get(0).overtimeTime()).isZero();
        }

        /** 法定休日労働は時間外労働に算入しない（労基法 36 条）ので、週の判定にも入れない。 */
        @Test
        @DisplayName("UT-BR04-08 法定休日労働は週 40 時間の判定に入れない")
        void legalHolidayIsExcluded() {
            var week = List.of(
                    days.fixedDay(SUN.plusDays(1), Duration.ofHours(8)),
                    days.fixedDay(SUN.plusDays(2), Duration.ofHours(8)),
                    days.fixedDay(SUN.plusDays(3), Duration.ofHours(8)),
                    days.fixedDay(SUN.plusDays(4), Duration.ofHours(8)),
                    days.fixedDay(SUN.plusDays(5), Duration.ofHours(8)),
                    // 日曜（法定休日）に 8 時間
                    legalHolidayOn(SUN, Duration.ofHours(8)));

            var weeks = rule.apply(week, List.of());

            assertThat(weeks.get(0).statutoryInsideTime())
                    .as("法定休日の 8 時間は含まない").isEqualTo(FORTY_HOURS);
            assertThat(weeks.get(0).overtimeTime()).isZero();
        }
    }

    @Nested
    @DisplayName("週の区切り")
    class WeekBoundary {

        /**
         * <strong>超過が発生した暦日の属する月に計上する</strong>（設計書 3.2）。
         *
         * <p>4/27(月)〜5/2(土) に 7 時間ずつ。40 時間に達するのは 5/1(金) の途中で、
         * 超過 2 時間は 5/1 の 1 時間と 5/2 の 1 時間に分かれる。どちらも 5 月。
         */
        @Test
        @DisplayName("UT-BR04-04 月をまたぐ週は超過が発生した日の属する月に計上される")
        void chargedToTheMonthTheExcessOccurred() {
            // 2026-04-26(日) 〜 2026-05-02(土) の週
            var weekStart = LocalDate.of(2026, 4, 26);
            var week = days.week(weekStart.plusDays(1), 6, Duration.ofHours(7));

            var weeks = rule.apply(week, List.of());

            assertThat(weeks.get(0).chargedMonths()).containsExactly(YearMonth.of(2026, 5));
            assertThat(rule.totalChargedTo(weeks, YearMonth.of(2026, 5)))
                    .isEqualTo(Duration.ofHours(2));
            assertThat(rule.totalChargedTo(weeks, YearMonth.of(2026, 4)))
                    .as("4 月には超過が発生していない").isZero();
        }

        /**
         * <strong>末日基準だと、この週の超過は誰にも計上されない。</strong>
         * 7/26(日)〜7/31(金) に 8 時間ずつ働き 7/31 に退職した社員を考える。
         * 週は 8/1(土) に終わるので、末日基準では 8 月に計上されるが、
         * 8 月は在籍していないので清算そのものが行われない。
         */
        @Test
        @DisplayName("UT-BR04-16 週の末日が翌月でも、超過が当月に発生していれば当月に計上する")
        void excessInTheCurrentMonthIsChargedToIt() {
            // 2026-07-26(日) 〜 8/1(土) の週。7/26〜7/31 の 6 日に 8 時間ずつ = 48 時間
            var week = days.week(LocalDate.of(2026, 7, 26), 6,
                    Duration.ofHours(8));

            var weeks = rule.apply(week, List.of());

            assertThat(weeks.get(0).weekEndExclusive()).isEqualTo(LocalDate.of(2026, 8, 2));
            assertThat(rule.totalChargedTo(weeks, YearMonth.of(2026, 7)))
                    .as("超過 8 時間は 7/31 に発生している")
                    .isEqualTo(Duration.ofHours(8));
            assertThat(rule.totalChargedTo(weeks, YearMonth.of(2026, 8)))
                    .as("8 月には 1 分も働いていない").isZero();
        }

        /**
         * 週が月をまたぎ、超過も月をまたぐ場合。
         * <strong>週の合計を片方の月へ寄せない。</strong>
         */
        @Test
        @DisplayName("UT-BR04-17 超過が月をまたぐと、日ごとにそれぞれの月へ分かれる")
        void excessSplitsAcrossMonths() {
            // 2026-04-26(日) 〜 5/2(土) に 8 時間ずつ 7 日 = 56 時間。
            // 40 時間に達するのは 5 日目（4/30）の終わり。超過 16 時間は 5/1 と 5/2 に 8 時間ずつ…
            // ではなく、4/26 から数えるので 4/26〜4/30 で 40 時間、5/1・5/2 が超過
            var week = days.week(LocalDate.of(2026, 4, 26), 7,
                    Duration.ofHours(8));

            var weeks = rule.apply(week, List.of());

            assertThat(weeks.get(0).overtimeTime()).isEqualTo(Duration.ofHours(16));
            assertThat(rule.totalChargedTo(weeks, YearMonth.of(2026, 4)))
                    .as("4/26〜4/30 の 40 時間で上限に達する").isZero();
            assertThat(rule.totalChargedTo(weeks, YearMonth.of(2026, 5)))
                    .isEqualTo(Duration.ofHours(16));
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
            var week = days.week(SUN, 7, Duration.ofHours(1));

            var weeks = rule.apply(week, List.of());

            assertThat(weeks).hasSize(1);
            assertThat(weeks.get(0).weekStart()).isEqualTo(SUN);
            assertThat(weeks.get(0).weekEndExclusive()).isEqualTo(SUN.plusDays(7));
        }

        @Test
        @DisplayName("次の日曜からは別の週になる")
        void nextSundayStartsANewWeek() {
            var twoDays = List.of(
                    days.fixedDay(SUN.plusDays(6), Duration.ofHours(8)),
                    days.fixedDay(SUN.plusDays(7), Duration.ofHours(8)));

            var weeks = rule.apply(twoDays, List.of());

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
                    Duration.ofHours(10), Duration.ofHours(11),
                    Map.of(YearMonth.of(2026, 4), Duration.ofHours(11))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("週の時間外が法定内労働を超えています");
        }

        @Test
        @DisplayName("日次勤怠が 1 件も無ければ週も無い")
        void noDays() {
            assertThat(rule.apply(List.of(), List.of())).isEmpty();
        }
    }
}
