package jp.co.sample.kintai.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.support.Punches;
import jp.co.sample.kintai.support.TestCalendar;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.NightWindow;

/**
 * <strong>就業規則の値が実際に計算へ効いていること</strong>を確かめる。
 *
 * <p>既定値だけでテストを書くと、計算が規則を読んでいるのか、
 * 同じ定数をたまたま使っているのかを区別できない。
 * 実際、深夜帯を {@code workRule.nightWindow()} から
 * {@code NightWindow.STANDARD} に、法定労働時間を
 * {@code workRule.statutoryDailyWorkingTime()} から {@code Duration.ofHours(8)} に
 * 書き換えても、288 件のテストがすべて通る状態だった。
 * 規則を改定しても計算が変わらない、という欠陥を素通りさせていたことになる。
 */
@DisplayName("就業規則の値が計算に反映される")
class WorkRuleDrivenCalculationTest {

    private static final LocalDate MON = LocalDate.of(2026, 4, 6);

    private static DailyAttendance calculate(Punches punches,
                                             jp.co.sample.kintai.workrule.domain.WorkRule rule) {
        return new DailyAttendanceCalculator(TestCalendar.allWorkdays())
                .calculate(MON, punches.build(), rule);
    }

    @Nested
    @DisplayName("所定労働時間（所定 < 法定）")
    class Scheduled {

        /**
         * 所定 7 時間の社員が 7 時間 30 分働くと、超過 30 分は
         * <strong>法定内残業</strong>（割増の支払義務なし）になる。
         *
         * <p>既定の規則は所定 8 時間 = 法定 8 時間なので、
         * 「所定の境界で切る」処理が一度も実行されていなかった。
         */
        @Test
        @DisplayName("所定 7 時間で 7 時間 30 分働くと法定内残業 30 分")
        void withinStatutoryOvertimeAppears() {
            var rule = WorkRules.rule(WorkRules.sevenHours());

            var result = calculate(Punches.on("2026-04-06")
                    .in("09:00").breakFrom("12:00").breakTo("13:00").out("17:30"), rule);

            assertThat(result.workingTime()).isEqualTo(Duration.ofMinutes(450));
            assertThat(result.baseTime()).isEqualTo(Duration.ofHours(7));
            assertThat(result.overtimeWithinStatutoryTime()).isEqualTo(Duration.ofMinutes(30));
            assertThat(result.overtimeBeyondStatutoryTime()).isZero();
        }

        /** 所定 7 時間で 10 時間働くと、法定内 1 時間・法定外 2 時間に分かれる。 */
        @Test
        @DisplayName("所定 7 時間で 10 時間働くと法定内 1 時間・法定外 2 時間")
        void bothOvertimeKindsAppear() {
            var rule = WorkRules.rule(WorkRules.sevenHours());

            var result = calculate(Punches.on("2026-04-06")
                    .in("09:00").breakFrom("12:00").breakTo("13:00").out("20:00"), rule);

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(10));
            assertThat(result.baseTime()).isEqualTo(Duration.ofHours(7));
            assertThat(result.overtimeWithinStatutoryTime()).isEqualTo(Duration.ofHours(1));
            assertThat(result.overtimeBeyondStatutoryTime()).isEqualTo(Duration.ofHours(2));
        }
    }

    @Nested
    @DisplayName("規則ごとの法定値")
    class StatutoryValues {

        /**
         * 法定労働時間は規則が持つ値を使う。
         * 8 時間をベタ書きすると、7 時間の規則でこのテストが落ちる。
         */
        @Test
        @DisplayName("法定 7 時間の規則では 7 時間を超えた分が法定外残業になる")
        void statutoryComesFromTheRule() {
            var rule = WorkRules.rule(WorkRules.sevenHours(), Duration.ofHours(7),
                    NightWindow.STANDARD);

            var result = calculate(Punches.on("2026-04-06")
                    .in("09:00").breakFrom("12:00").breakTo("13:00").out("18:00"), rule);

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.baseTime()).isEqualTo(Duration.ofHours(7));
            assertThat(result.overtimeWithinStatutoryTime())
                    .as("所定 7 時間 = 法定 7 時間なので法定内残業は生じない")
                    .isZero();
            assertThat(result.overtimeBeyondStatutoryTime()).isEqualTo(Duration.ofHours(1));
        }

        /**
         * 深夜帯も規則が持つ値を使う。
         * {@code DESIGNATED_AREA}（23:00–06:00）は原則より 1 時間後ろにずれる。
         */
        @Test
        @DisplayName("深夜帯は規則の値を使う（23:00–06:00 の地域）")
        void nightWindowComesFromTheRule() {
            var standard = WorkRules.rule(WorkRules.fixed(), Duration.ofHours(8),
                    NightWindow.STANDARD);
            var designated = WorkRules.rule(WorkRules.fixed(), Duration.ofHours(8),
                    NightWindow.DESIGNATED_AREA);
            var punches = Punches.on("2026-04-06").in("21:00").out("2026-04-07T01:00");

            assertThat(calculate(punches, standard).nightTime())
                    .as("22:00–01:00 の 3 時間")
                    .isEqualTo(Duration.ofHours(3));
            assertThat(calculate(punches, designated).nightTime())
                    .as("23:00–01:00 の 2 時間")
                    .isEqualTo(Duration.ofHours(2));
        }
    }

    @Nested
    @DisplayName("規則の有効期間")
    class ValidPeriod {

        /**
         * 時点解決は {@code EffectiveWorkRule} の責務だが、
         * その結果を取り違えて渡されたときに<strong>黙って古い規則で計算しない</strong>。
         */
        @Test
        @DisplayName("勤務日を含まない版を渡すと例外になる")
        void ruleMustCoverTheWorkDate() {
            var expired = new jp.co.sample.kintai.workrule.domain.WorkRule(
                    new jp.co.sample.kintai.workrule.domain.WorkRuleId(
                            java.util.UUID.randomUUID()),
                    new jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId(
                            java.util.UUID.randomUUID()),
                    new jp.co.sample.kintai.shared.domain.DateRange(
                            LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1)),
                    WorkRules.fixed(), Duration.ofHours(8), Duration.ofHours(40),
                    NightWindow.STANDARD,
                    jp.co.sample.kintai.workrule.domain.PremiumRates.STATUTORY);

            assertThatThrownBy(() -> calculate(
                    Punches.on("2026-04-06").in("09:00").out("18:00"), expired))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("就業規則の有効期間が勤務日を含んでいません");
        }
    }
}
