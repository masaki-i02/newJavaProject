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
 * 36 協定の上限監視（UT-BR12-01〜10）。
 *
 * <p>法定内残業を数えないことは、この record では表現できない。
 * 法定内残業は日次で別の区分に分けられ {@code overtimeTime} に届かないので、
 * <strong>月次清算を通して検査する</strong>（UT-BR12-03 / UT-BR04-13）。
 *
 * <p><strong>2 つの規制で対象が違うことが要点である。</strong>
 * 限度時間（月 45 時間・年 360 時間）の対象は時間外労働だけで、休日労働は含まない
 * （36 条 3 項・4 項）。休日労働を含めるのは 6 項 2 号の単月 100 時間未満である。
 * 法定内残業はどちらにも含めない。時間外労働ではないため。
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
         * <strong>休日労働は限度時間の対象ではない</strong>（36 条 3 項）。
         * 第 1 版は合算しており、月 1 回の休日出勤があるだけで
         * <strong>適法な月に偽の警告が立っていた。</strong>
         */
        @Test
        @DisplayName("UT-BR12-02 時間外 44 時間 + 法定休日 8 時間では限度時間の警告が立たない")
        void legalHolidayIsNotSubjectToTheLimit() {
            var result = usage(Duration.ofHours(44), Duration.ofHours(8));

            assertThat(result.subjectTime())
                    .as("限度時間の対象は時間外労働だけ").isEqualTo(Duration.ofHours(44));
            assertThat(result.combinedTime())
                    .as("6 項の対象は時間外 + 休日").isEqualTo(Duration.ofHours(52));
            assertThat(result.exceedsMonthly()).isFalse();
            assertThat(result.hasWarning()).isFalse();
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
    @DisplayName("時間外 + 休日の単月上限（100 時間未満・36 条 6 項 2 号）")
    class CombinedSingleMonth {

        /**
         * <strong>「以下」ではなく「未満」。</strong> ちょうど 100 時間で違反になる。
         * 限度時間（45 時間を「超えた」場合）と向きが違うので、
         * 同じ書き方をすると 1 か月ぶん見逃す。
         */
        @Test
        @DisplayName("UT-BR12-08 時間外 44 時間 + 法定休日 56 時間（ちょうど 100 時間）で触れる")
        void exactlyAtTheCombinedLimit() {
            var result = usage(Duration.ofHours(44), Duration.ofHours(56));

            assertThat(result.exceedsMonthly()).as("限度時間は超えていない").isFalse();
            assertThat(result.exceedsCombinedSingleMonth()).isTrue();
            assertThat(result.hasWarning()).isTrue();
        }

        @Test
        @DisplayName("UT-BR12-09 合計 99 時間ならどちらの警告も立たない")
        void justBelowTheCombinedLimit() {
            var result = usage(Duration.ofHours(44), Duration.ofHours(55));

            assertThat(result.exceedsCombinedSingleMonth()).isFalse();
            assertThat(result.hasWarning()).isFalse();
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

        /** 年次上限も限度時間なので、休日労働は数えない。 */
        @Test
        @DisplayName("UT-BR12-10 年度累計に法定休日労働を数えない")
        void legalHolidayIsNotCountedTowardTheAnnualLimit() {
            var result = AgreementUsage.of(Duration.ofHours(20), Duration.ofHours(100),
                    Duration.ofHours(340));

            assertThat(result.annualUsed())
                    .as("時間外 20 時間だけを足す").isEqualTo(Duration.ofHours(360));
            assertThat(result.exceedsAnnual()).isFalse();
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
