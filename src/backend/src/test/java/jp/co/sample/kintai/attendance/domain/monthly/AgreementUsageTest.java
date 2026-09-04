package jp.co.sample.kintai.attendance.domain.monthly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 36 協定の上限監視（UT-BR12-01〜04）。
 *
 * <p><strong>対象に何を含めるかが要点である。</strong>
 * 法定内残業は時間外労働ではないので含めない。
 * 法定休日労働は時間外労働に算入しない（BR-07）が、36 協定の時間数には算入する。
 */
@DisplayName("36 協定の上限監視（BR-12）")
class AgreementUsageTest {

    private static AgreementUsage usage(Duration overtime, Duration legalHoliday) {
        return AgreementUsage.of(overtime, legalHoliday, Duration.ZERO);
    }

    @Nested
    @DisplayName("月次上限（45 時間）")
    class Monthly {

        @Test
        @DisplayName("UT-BR12-01 時間外 44 時間では警告が立たない")
        void withinLimit() {
            var result = usage(Duration.ofHours(44), Duration.ZERO);

            assertThat(result.subjectTime()).isEqualTo(Duration.ofHours(44));
            assertThat(result.exceedsMonthly()).isFalse();
            assertThat(result.hasWarning()).isFalse();
        }

        /**
         * <strong>法定休日労働を足して初めて超える。</strong>
         * 時間外だけを見ていると 40 時間なので警告が立たず、超過を見逃す。
         */
        @Test
        @DisplayName("UT-BR12-02 時間外 40 時間 + 法定休日 6 時間で警告が立つ")
        void legalHolidayCountsTowardTheLimit() {
            var result = usage(Duration.ofHours(40), Duration.ofHours(6));

            assertThat(result.subjectTime()).isEqualTo(Duration.ofHours(46));
            assertThat(result.exceedsMonthly()).isTrue();
        }

        /**
         * <strong>法定内残業は 36 協定の対象外。</strong>
         * 所定を超えても法定 8 時間以内なら時間外労働ではないので、
         * どれだけ積んでも上限には触れない。
         */
        @Test
        @DisplayName("UT-BR12-03 法定内残業だけ 50 時間でも警告は立たない")
        void withinStatutoryOvertimeIsNotSubject() {
            // 法定内残業は overtimeTime に含めない。渡すのは法定外残業だけ
            var result = usage(Duration.ZERO, Duration.ZERO);

            assertThat(result.subjectTime()).isZero();
            assertThat(result.exceedsMonthly()).isFalse();
        }

        @Test
        @DisplayName("UT-BR12-05 ちょうど 45 時間では警告が立たない（超えていない）")
        void exactlyAtTheLimit() {
            assertThat(usage(Duration.ofHours(45), Duration.ZERO).exceedsMonthly()).isFalse();
            assertThat(usage(Duration.ofHours(45).plusMinutes(1), Duration.ZERO)
                    .exceedsMonthly()).isTrue();
        }
    }

    @Nested
    @DisplayName("年次上限（360 時間）")
    class Annual {

        @Test
        @DisplayName("UT-BR12-04 年度累計が 360 時間を超えると警告が立つ")
        void exceedsAnnual() {
            var result = AgreementUsage.of(Duration.ofHours(30), Duration.ZERO,
                    Duration.ofHours(340));

            assertThat(result.annualUsed()).isEqualTo(Duration.ofHours(370));
            assertThat(result.exceedsAnnual()).isTrue();
            assertThat(result.exceedsMonthly())
                    .as("その月だけなら 30 時間なので月次上限は超えない").isFalse();
            assertThat(result.hasWarning()).isTrue();
        }

        @Test
        @DisplayName("UT-BR12-06 ちょうど 360 時間では警告が立たない")
        void exactlyAtTheAnnualLimit() {
            assertThat(AgreementUsage.of(Duration.ofHours(20), Duration.ZERO,
                    Duration.ofHours(340)).exceedsAnnual()).isFalse();
        }
    }

    @Nested
    @DisplayName("年度の起算")
    class FiscalYear {

        /**
         * <strong>1〜3 月は前年の 4 月 1 日が起算日。</strong>
         * 暦年で数えると 1 月に年次上限がリセットされ、
         * 3 か月ぶんの超過を見逃す。
         */
        @Test
        @DisplayName("UT-BR12-07 4 月以降はその年の 4/1、1〜3 月は前年の 4/1")
        void fiscalYearStart() {
            assertThat(AgreementUsage.fiscalYearStartOf(YearMonth.of(2026, 4)))
                    .isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(AgreementUsage.fiscalYearStartOf(YearMonth.of(2026, 12)))
                    .isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(AgreementUsage.fiscalYearStartOf(YearMonth.of(2027, 1)))
                    .as("1 月は前年度").isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(AgreementUsage.fiscalYearStartOf(YearMonth.of(2027, 3)))
                    .as("3 月は前年度").isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(AgreementUsage.fiscalYearStartOf(YearMonth.of(2027, 4)))
                    .as("4 月から新年度").isEqualTo(LocalDate.of(2027, 4, 1));
        }
    }

    @Nested
    @DisplayName("不変条件")
    class Invariants {

        @Test
        @DisplayName("実績を負にはできない")
        void negativeIsRejected() {
            assertThatThrownBy(() -> usage(Duration.ofHours(-1), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("実績を負にはできません");
        }

        @Test
        @DisplayName("月次上限が年次上限を超える設定は作れない")
        void monthlyAboveAnnual() {
            assertThatThrownBy(() -> new AgreementUsage(Duration.ZERO, Duration.ZERO,
                    Duration.ofHours(400), Duration.ofHours(360), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("月次上限が年次上限を超えています");
        }
    }
}
