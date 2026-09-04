package jp.co.sample.kintai.leave.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * 付与日と算定期間の導出（BR-14）。
 *
 * <p>型名とメソッド名は ASCII にする。日本語にすると {@code .class} と
 * レポート HTML のファイル名になり、POSIX / C ロケールで書き出しに失敗する
 * （CLAUDE.md 落とし穴 74）。業務の言葉は {@code @DisplayName} に置く。
 */
@DisplayName("年次有給休暇の付与日と算定期間")
class GrantScheduleTest {

    @Nested
    @DisplayName("付与日")
    class GrantDate {

        @Test
        @DisplayName("UT-LV-01 入社から 6 か月後が 0 回目の付与日")
        void first() {
            var schedule = new GrantSchedule(LocalDate.of(2026, 4, 1));
            assertThat(schedule.grantDateOf(0)).isEqualTo(LocalDate.of(2026, 10, 1));
        }

        @Test
        @DisplayName("UT-LV-02 1 回目以降は 1 年ごと")
        void yearly() {
            var schedule = new GrantSchedule(LocalDate.of(2026, 4, 1));
            assertThat(schedule.grantDateOf(1)).isEqualTo(LocalDate.of(2027, 10, 1));
            assertThat(schedule.grantDateOf(2)).isEqualTo(LocalDate.of(2028, 10, 1));
        }

        /** 月末入社は {@code plusMonths} の丸めに従う。独自の丸めを持ち込まない。 */
        @Test
        @DisplayName("UT-LV-03 1/31 入社の 6 か月後は 7/31")
        void endOfMonth() {
            var schedule = new GrantSchedule(LocalDate.of(2026, 1, 31));
            assertThat(schedule.grantDateOf(0)).isEqualTo(LocalDate.of(2026, 7, 31));
        }

        /**
         * <strong>応当日が無い月末入社。</strong>
         * 民法 143 条 2 項では期間は 2 月末に満了し、法定の付与日はその翌日になる。
         * {@code plusMonths} は満了日そのものを返すので 1 日早いが、
         * 労働者に有利な前倒しなのでこれを採る。
         */
        @Test
        @DisplayName("UT-LV-04 8/31 入社の 6 か月後はうるう年の 2/29")
        void leapYear() {
            var leap = new GrantSchedule(LocalDate.of(2023, 8, 31));
            assertThat(leap.grantDateOf(0)).isEqualTo(LocalDate.of(2024, 2, 29));

            var common = new GrantSchedule(LocalDate.of(2024, 8, 31));
            assertThat(common.grantDateOf(0)).isEqualTo(LocalDate.of(2025, 2, 28));
        }
    }

    @Nested
    @DisplayName("付与日数")
    class Days {

        @Test
        @DisplayName("UT-LV-05 付与日数の表（0〜6 回目）")
        void table() {
            var schedule = new GrantSchedule(LocalDate.of(2026, 4, 1));
            assertThat(new int[] {
                    schedule.daysOf(0), schedule.daysOf(1), schedule.daysOf(2),
                    schedule.daysOf(3), schedule.daysOf(4), schedule.daysOf(5),
                    schedule.daysOf(6)})
                    .containsExactly(10, 11, 12, 14, 16, 18, 20);
        }

        @Test
        @DisplayName("UT-LV-06 7 回目以降も 20 日で頭打ち")
        void capped() {
            var schedule = new GrantSchedule(LocalDate.of(2026, 4, 1));
            assertThat(schedule.daysOf(7)).isEqualTo(20);
            assertThat(schedule.daysOf(30)).isEqualTo(20);
        }

        @Test
        @DisplayName("UT-LV-17 付与の連番が負なら例外")
        void negative() {
            assertThatThrownBy(() -> LeaveEntitlement.of(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("出勤率の算定期間")
    class AssessmentPeriod {

        @Test
        @DisplayName("UT-LV-07 0 回目は入社日から付与日まで（半開区間）")
        void first() {
            var schedule = new GrantSchedule(LocalDate.of(2026, 4, 1));
            assertThat(schedule.assessmentPeriodOf(0)).isEqualTo(
                    new DateRange(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 1)));
        }

        @Test
        @DisplayName("UT-LV-08 1 回目は前回付与日から今回付与日まで")
        void second() {
            var schedule = new GrantSchedule(LocalDate.of(2026, 4, 1));
            assertThat(schedule.assessmentPeriodOf(1)).isEqualTo(
                    new DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 10, 1)));
        }
    }

    @Nested
    @DisplayName("到来した付与")
    class DueIndexes {

        /** <strong>古い順であることが必要。</strong> 1 回目の判定に 0 回目の取得日が要る。 */
        @Test
        @DisplayName("UT-LV-16 不付与でも連番は進むので、到来分をすべて古い順に返す")
        void ordered() {
            var schedule = new GrantSchedule(LocalDate.of(2026, 4, 1));
            assertThat(schedule.indexesDueOn(LocalDate.of(2028, 10, 1)).boxed().toList())
                    .containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("付与日が到来していなければ空")
        void notYet() {
            var schedule = new GrantSchedule(LocalDate.of(2026, 4, 1));
            assertThat(schedule.indexesDueOn(LocalDate.of(2026, 9, 30)).boxed().toList())
                    .isEmpty();
        }

        /** 付与日<strong>当日</strong>に付与される。前日には付与されない。 */
        @Test
        @DisplayName("付与日の当日は到来している")
        void onGrantDate() {
            var schedule = new GrantSchedule(LocalDate.of(2026, 4, 1));
            assertThat(schedule.indexesDueOn(LocalDate.of(2026, 10, 1)).boxed().toList())
                    .containsExactly(0);
        }
    }
}
