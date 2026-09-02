package jp.co.sample.kintai.workrule.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.DateRange;

/** 清算期間の単体テスト（UT-WR-16・17）。 */
@DisplayName("清算期間")
class SettlementPeriodTest {

    private static final Duration WEEKLY = Duration.ofHours(40);

    @Test
    @DisplayName("通常の月は暦月そのものになる")
    void fullMonth() {
        var period = SettlementPeriod.of(YearMonth.of(2026, 6),
                DateRange.startingAt(LocalDate.of(2020, 4, 1))).orElseThrow();

        assertThat(period.period()).isEqualTo(
                new DateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)));
        assertThat(period.days()).isEqualTo(30);
    }

    @Test
    @DisplayName("UT-WR-16 4/15 入社の初月は [2026-04-15, 2026-05-01)")
    void midMonthHire() {
        var period = SettlementPeriod.of(YearMonth.of(2026, 4),
                DateRange.startingAt(LocalDate.of(2026, 4, 15))).orElseThrow();

        assertThat(period.period()).isEqualTo(
                new DateRange(LocalDate.of(2026, 4, 15), LocalDate.of(2026, 5, 1)));
        assertThat(period.days()).isEqualTo(16);
    }

    /** 退職日は最終在籍日なので、半開区間の上限は翌日になる。 */
    @Test
    @DisplayName("UT-WR-17 9/20 退職の最終月は [2026-09-01, 2026-09-21)")
    void midMonthRetirement() {
        var employment = DateRange.closed(LocalDate.of(2020, 4, 1), LocalDate.of(2026, 9, 20));
        var period = SettlementPeriod.of(YearMonth.of(2026, 9), employment).orElseThrow();

        assertThat(period.period()).isEqualTo(
                new DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 21)));
        assertThat(period.days()).isEqualTo(20);
    }

    @Test
    @DisplayName("在籍していない月は清算期間が存在しない")
    void beforeHire() {
        assertThat(SettlementPeriod.of(YearMonth.of(2026, 3),
                DateRange.startingAt(LocalDate.of(2026, 4, 15)))).isEmpty();
    }

    /**
     * 暦月で計算すると総枠が 10,285 分になり、
     * <strong>時間外労働が計上されずに賃金が不足する。</strong>
     */
    @Test
    @DisplayName("月中入社の総枠は在籍期間で決まる（16 日 → 5,485 分）")
    void limitFollowsThePeriod() {
        var midMonth = SettlementPeriod.of(YearMonth.of(2026, 4),
                DateRange.startingAt(LocalDate.of(2026, 4, 15))).orElseThrow();
        var fullMonth = SettlementPeriod.of(YearMonth.of(2026, 4),
                DateRange.startingAt(LocalDate.of(2020, 4, 1))).orElseThrow();

        assertThat(midMonth.statutoryTotalLimit(WEEKLY)).isEqualTo(Duration.ofMinutes(5_485));
        assertThat(fullMonth.statutoryTotalLimit(WEEKLY)).isEqualTo(Duration.ofMinutes(10_285));
    }

    @Test
    @DisplayName("総枠は分未満を切り捨てる（労働者に有利な方向）")
    void limitTruncatesToMinutes() {
        var june = SettlementPeriod.of(YearMonth.of(2026, 6),
                DateRange.startingAt(LocalDate.of(2020, 4, 1))).orElseThrow();
        var may = SettlementPeriod.of(YearMonth.of(2026, 5),
                DateRange.startingAt(LocalDate.of(2020, 4, 1))).orElseThrow();

        assertThat(june.statutoryTotalLimit(WEEKLY))
                .as("30 ÷ 7 × 2400 = 10285.71 → 10285")
                .isEqualTo(Duration.ofMinutes(10_285));
        assertThat(may.statutoryTotalLimit(WEEKLY))
                .as("31 ÷ 7 × 2400 = 10628.57 → 10628")
                .isEqualTo(Duration.ofMinutes(10_628));
    }

    /**
     * <strong>レビューで見つかった状況。</strong>
     * 所定総が総枠を上回る月がある。所定どおり働くだけで法定外残業が発生する。
     */
    @Test
    @DisplayName("UT-WR-18 2026-06 は所定総（22 日 × 8 時間）が法定総枠を超える")
    void scheduleCanExceedTheLimit() {
        var june = SettlementPeriod.of(YearMonth.of(2026, 6),
                DateRange.startingAt(LocalDate.of(2020, 4, 1))).orElseThrow();
        Duration scheduledTotal = Duration.ofMinutes(22 * 480);

        assertThat(scheduledTotal).isEqualTo(Duration.ofMinutes(10_560));
        assertThat(scheduledTotal).isGreaterThan(june.statutoryTotalLimit(WEEKLY));
    }

    @Test
    @DisplayName("UT-WR-19 所定総が総枠を超えない月では警告は立たない")
    void scheduleWithinTheLimit() {
        var may = SettlementPeriod.of(YearMonth.of(2026, 5),
                DateRange.startingAt(LocalDate.of(2020, 4, 1))).orElseThrow();
        Duration scheduledTotal = Duration.ofMinutes(20 * 480);

        assertThat(scheduledTotal).isLessThanOrEqualTo(may.statutoryTotalLimit(WEEKLY));
    }
}
