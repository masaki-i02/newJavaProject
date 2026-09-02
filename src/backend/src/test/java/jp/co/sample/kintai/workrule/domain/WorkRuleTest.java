package jp.co.sample.kintai.workrule.domain;

import static jp.co.sample.kintai.support.WorkRules.fixed;
import static jp.co.sample.kintai.support.WorkRules.flex;
import static jp.co.sample.kintai.support.WorkRules.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.PremiumType;
import jp.co.sample.kintai.shared.domain.TimeOfDayRange;
import jp.co.sample.kintai.shared.domain.TimeRange;

/** 就業規則の単体テスト（UT-WR-01〜19）。 */
@DisplayName("就業規則")
class WorkRuleTest {

    @Nested
    @DisplayName("労働時間制度")
    class Systems {

        @Test
        @DisplayName("UT-WR-01 固定時間制の所定労働時間（9:00–18:00 / 休憩 60 分 → 8 時間）")
        void scheduledWorkingTime() {
            assertThat(fixed().scheduledWorkingTime()).isEqualTo(Duration.ofHours(8));
        }

        @Test
        @DisplayName("UT-WR-02 日をまたぐ固定勤務（22:00–06:00 / 休憩 60 分 → 7 時間）")
        void overnightScheduledWorkingTime() {
            assertThat(new FixedTimeSystem(LocalTime.of(22, 0), LocalTime.of(6, 0),
                    Duration.ofMinutes(60)).scheduledWorkingTime())
                    .isEqualTo(Duration.ofHours(7));
        }

        @Test
        @DisplayName("UT-WR-03 フレックスの所定総労働時間（20 日 × 8 時間 → 160 時間）")
        void scheduledTotalWorkingTime() {
            assertThat(flex().scheduledTotalWorkingTime(20)).isEqualTo(Duration.ofHours(160));
        }

        @Test
        @DisplayName("UT-WR-12 所定 7 時間なのに休憩 30 分だと生成できない（労基法 34 条）")
        void breakBelowLegalMinimum() {
            assertThatThrownBy(() -> new FixedTimeSystem(
                    LocalTime.of(9, 0), LocalTime.of(16, 30), Duration.ofMinutes(30)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("休憩は 45 分以上必要です");
        }

        @Test
        @DisplayName("コアタイムがフレキシブルタイムの外にあると生成できない")
        void coreOutsideFlexible() {
            assertThatThrownBy(() -> new FlextimeSystem(
                    new TimeOfDayRange(LocalTime.of(7, 0), LocalTime.of(22, 0)),
                    new TimeOfDayRange(LocalTime.of(6, 0), LocalTime.of(15, 0)),
                    Duration.ofHours(8)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("コアタイムはフレキシブルタイムの内側");
        }

        /**
         * 始業と終業が同じ時刻は<strong>24 時間拘束</strong>を意味する。
         * 0 時間ではない。ここを 0 と読むと、所定 0 の規則が作れてしまい、
         * 出勤した瞬間から全時間が残業になる。
         */
        @Test
        @DisplayName("始業と終業が同じ時刻なら 24 時間拘束として扱う")
        void startEqualToEndMeansFullDay() {
            var allDay = new FixedTimeSystem(LocalTime.of(9, 0), LocalTime.of(9, 0),
                    Duration.ofHours(1));

            assertThat(allDay.scheduledWorkingTime()).isEqualTo(Duration.ofHours(23));
        }

        @Test
        @DisplayName("終業が始業より前なら日をまたぐ勤務として扱う")
        void endBeforeStartCrossesMidnight() {
            var overnight = new FixedTimeSystem(LocalTime.of(22, 0), LocalTime.of(7, 0),
                    Duration.ofHours(1));

            assertThat(overnight.scheduledWorkingTime()).isEqualTo(Duration.ofHours(8));
        }

        /** 休憩が拘束時間を食い尽くす規則は、所定労働時間が 0 以下になる。 */
        @Test
        @DisplayName("休憩が拘束時間を超える規則は作れない")
        void breakLongerThanSpanIsRejected() {
            assertThatThrownBy(() -> new FixedTimeSystem(LocalTime.of(9, 0), LocalTime.of(12, 0),
                    Duration.ofHours(4)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("所定労働時間が 0 以下です");
        }

        @Test
        @DisplayName("フレキシブル帯はコアタイムから導出される")
        void flexibleBandsAreDerived() {
            assertThat(flex().flexibleMorning())
                    .contains(new TimeOfDayRange(LocalTime.of(7, 0), LocalTime.of(11, 0)));
            assertThat(flex().flexibleEvening())
                    .contains(new TimeOfDayRange(LocalTime.of(15, 0), LocalTime.of(22, 0)));
        }
    }

    @Nested
    @DisplayName("法定値の範囲")
    class StatutoryLimits {

        @Test
        @DisplayName("UT-WR-04 割増率が法定下限を下回ると生成できない")
        void premiumRateBelowMinimum() {
            assertThatThrownBy(() -> new PremiumRates(new BigDecimal("0.10"),
                    new BigDecimal("0.25"), new BigDecimal("0.35")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("法定外残業の割増率が法定下限を下回っています");
        }

        /**
         * 深夜帯は {@code enum} なので、02:00–03:00 のような値は
         * <strong>そもそも書けない</strong>（コンパイルが通らない）。
         * ここで確かめられるのは「法が認める 2 つ以外が増えていないこと」だけである。
         */
        @Test
        @DisplayName("UT-WR-09 深夜帯は 22:00–05:00 と 23:00–06:00 の 2 つしか存在しない")
        void nightWindowIsClosedSet() {
            assertThat(NightWindow.values())
                    .extracting(NightWindow::start, NightWindow::end)
                    .containsExactlyInAnyOrder(
                            tuple(LocalTime.of(22, 0), LocalTime.of(5, 0)),
                            tuple(LocalTime.of(23, 0), LocalTime.of(6, 0)));
        }

        /**
         * 割増率の下限だけ守っても、法定労働時間を 12 時間にされたら
         * <strong>割増の対象そのものが消える</strong>（CLAUDE.md 落とし穴 15）。
         */
        @Test
        @DisplayName("UT-WR-10 1 日の法定労働時間を 12 時間では生成できない")
        void statutoryDailyAboveMaximum() {
            assertThatThrownBy(() -> new WorkRule(
                    new WorkRuleId(UUID.randomUUID()), new WorkRuleSeriesId(UUID.randomUUID()),
                    DateRange.startingAt(LocalDate.of(2026, 1, 1)), fixed(),
                    Duration.ofHours(12), Duration.ofHours(40),
                    NightWindow.STANDARD, PremiumRates.STATUTORY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1 日の法定労働時間が法定の上限を超えています");
        }

        @Test
        @DisplayName("UT-WR-11 所定が法定を超える規則は生成できない")
        void scheduledExceedsStatutory() {
            assertThatThrownBy(() -> new WorkRule(
                    new WorkRuleId(UUID.randomUUID()), new WorkRuleSeriesId(UUID.randomUUID()),
                    DateRange.startingAt(LocalDate.of(2026, 1, 1)),
                    new FixedTimeSystem(LocalTime.of(9, 0), LocalTime.of(19, 0),
                            Duration.ofMinutes(60)),
                    Duration.ofHours(8), Duration.ofHours(40),
                    NightWindow.STANDARD, PremiumRates.STATUTORY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("所定労働時間が法定労働時間を超えています");
        }
    }

    @Nested
    @DisplayName("割増の倍率")
    class Multipliers {

        private final PremiumRates rates = PremiumRates.STATUTORY;

        @Test
        @DisplayName("UT-WR-05 深夜 + 法定外残業 → 1.50")
        void nightAndOvertime() {
            assertThat(rates.multiplierFor(
                    Set.of(PremiumType.NIGHT, PremiumType.OVERTIME_BEYOND_STATUTORY)))
                    .isEqualByComparingTo("1.50");
        }

        @Test
        @DisplayName("UT-WR-06 深夜 + 法定休日 → 1.60（法定休日と時間外は重複しない）")
        void nightAndLegalHoliday() {
            assertThat(rates.multiplierFor(
                    Set.of(PremiumType.NIGHT, PremiumType.LEGAL_HOLIDAY)))
                    .isEqualByComparingTo("1.60");
        }

        @Test
        @DisplayName("法定内残業に割増の支払義務は無い → 1.00")
        void overtimeWithinStatutoryHasNoPremium() {
            assertThat(rates.multiplierFor(Set.of(PremiumType.OVERTIME_WITHIN_STATUTORY)))
                    .isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("属性が無ければ 1.00")
        void plainIsOne() {
            assertThat(rates.multiplierFor(Set.of())).isEqualByComparingTo("1.00");
        }
    }

    @Nested
    @DisplayName("深夜帯の展開")
    class NightWindowExpansion {

        private final NightWindow night = NightWindow.STANDARD;

        @Test
        @DisplayName("UT-WR-07 20:00–翌 02:00 の区間 → 深夜は 22:00–翌 02:00 の 4 時間")
        void crossingMidnight() {
            var range = new TimeRange(LocalDateTime.parse("2026-04-07T20:00"),
                    LocalDateTime.parse("2026-04-08T02:00"));

            assertThat(night.intervalsOverlapping(range)).containsExactly(
                    new TimeRange(LocalDateTime.parse("2026-04-07T22:00"),
                            LocalDateTime.parse("2026-04-08T02:00")));
        }

        @Test
        @DisplayName("UT-WR-08 連続勤務では深夜帯が 2 回現れる")
        void twoNightsInOneRange() {
            var range = new TimeRange(LocalDateTime.parse("2026-04-07T20:00"),
                    LocalDateTime.parse("2026-04-09T02:00"));

            assertThat(night.intervalsOverlapping(range)).containsExactly(
                    new TimeRange(LocalDateTime.parse("2026-04-07T22:00"),
                            LocalDateTime.parse("2026-04-08T05:00")),
                    new TimeRange(LocalDateTime.parse("2026-04-08T22:00"),
                            LocalDateTime.parse("2026-04-09T02:00")));
        }

        @Test
        @DisplayName("深夜帯に一切かからない区間では空になる")
        void noOverlap() {
            var range = new TimeRange(LocalDateTime.parse("2026-04-07T09:00"),
                    LocalDateTime.parse("2026-04-07T18:00"));

            assertThat(night.intervalsOverlapping(range)).isEmpty();
        }
    }

    @Test
    @DisplayName("判別値は sealed interface から導かれる")
    void systemTypeIsDerived() {
        assertThat(rule(fixed()).systemType()).isEqualTo(WorkingTimeSystemType.FIXED);
        assertThat(rule(flex()).systemType()).isEqualTo(WorkingTimeSystemType.FLEX);
    }
}
