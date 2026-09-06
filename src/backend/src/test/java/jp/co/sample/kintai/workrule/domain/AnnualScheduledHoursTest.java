package jp.co.sample.kintai.workrule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * 1 か月平均所定労働時間数（UT-PAY-15・労基則 19 条 1 項 4 号）。
 *
 * <p><strong>1 日の所定が 8 時間ちょうどでは、切り捨ては一度も起きない。</strong>
 * 480 ÷ 12 = 40 なので、日数が何日でも割り切れる。
 * 245 日 × 480 分 = 117,600 → 9,800 という設計書の例でテストを書くと、
 * <strong>切り上げにも四捨五入にも変えられて落ちない</strong>（CLAUDE.md 落とし穴 24・43）。
 *
 * <p>就業規則は所定を 1 分単位で持てるので、12 の倍数でない所定を使って確かめる。
 * 余りが 9（7 時間 45 分）なら切り上げと四捨五入の両方が、
 * 余りが 3（7 時間 35 分）なら切り上げだけが死ぬ。<strong>両側から挟む。</strong>
 */
@DisplayName("1 か月平均所定労働時間数（BR-18）")
class AnnualScheduledHoursTest {

    /** 2026 年度。365 日。 */
    private static final DateRange FY2026 =
            new DateRange(LocalDate.of(2026, 4, 1), LocalDate.of(2027, 4, 1));

    @Test
    @DisplayName("UT-PAY-15 年間の所定を 12 で割り、分未満を切り捨てる")
    void monthlyAverageTruncatesToMinutes() {
        // 261 日 × 465 分（7 時間 45 分）= 121,365 → ÷12 = 10,113.75
        var withRemainderNine = AnnualScheduledHours.of(2026, 261,
                Duration.ofMinutes(121_365));
        assertThat(withRemainderNine.monthlyAverage())
                .as("切り上げ（10114）でも四捨五入（10114）でもなく、切り捨て")
                .isEqualTo(Duration.ofMinutes(10_113));

        // 261 日 × 455 分（7 時間 35 分）= 118,755 → ÷12 = 9,896.25
        var withRemainderThree = AnnualScheduledHours.of(2026, 261,
                Duration.ofMinutes(118_755));
        assertThat(withRemainderThree.monthlyAverage())
                .as("四捨五入では 9896 のままなので、切り上げ（9897）を殺す")
                .isEqualTo(Duration.ofMinutes(9_896));

        assertThat(withRemainderNine.period())
                .as("年度は 4 月 〜 翌 3 月").isEqualTo(FY2026);
        assertThat(withRemainderNine.scheduledDays()).isEqualTo(261);
        assertThat(withRemainderNine.annualTotal())
                .as("導出元も持つ。月平均だけでは検算できない")
                .isEqualTo(Duration.ofMinutes(121_365));
    }

    /**
     * <strong>1 月〜3 月は前の年の年度である。</strong>
     * 暦年と混ぜると、同じ月がどちらの年度かで違う分母を持つことになる。
     */
    @Test
    @DisplayName("UT-PAY-15 対象月が属する年度は 4 月始まり")
    void fiscalYearStartsInApril() {
        assertThat(AnnualScheduledHours.fiscalYearOf(YearMonth.of(2026, 4))).isEqualTo(2026);
        assertThat(AnnualScheduledHours.fiscalYearOf(YearMonth.of(2026, 12))).isEqualTo(2026);
        assertThat(AnnualScheduledHours.fiscalYearOf(YearMonth.of(2027, 3)))
                .as("3 月分の給与を 4 月に出しても、年度は 2026").isEqualTo(2026);
        assertThat(AnnualScheduledHours.fiscalYearOf(YearMonth.of(2027, 4))).isEqualTo(2027);
    }

    /**
     * 導出できる値を持つので、食い違いを型で禁じる（落とし穴 39）。
     * DB の {@code payroll_exports_average_derivation_check} と同じ式である。
     */
    @Test
    @DisplayName("UT-PAY-15 月平均が年間の所定 ÷ 12 と食い違うと生成できない")
    void monthlyAverageMustBeDerivedFromAnnualTotal() {
        assertThatThrownBy(() -> new AnnualScheduledHours(2026, FY2026, 261,
                Duration.ofMinutes(121_365), Duration.ofMinutes(10_114)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("月平均が年間の所定 ÷ 12 と一致しません");
    }
}
