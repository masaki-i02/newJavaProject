package jp.co.sample.kintai.workrule.domain;

import static jp.co.sample.kintai.support.WorkRules.flex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * 退職日は最終在籍日なので、半開区間の上限は翌日になる。
     *
     * <p>ここを閉区間の感覚で数えると<strong>退職日当日の 1 日が消える</strong>
     * （CLAUDE.md 落とし穴 10）。逆に上限を「翌日」と数えたうえで日数を 21 と書くと、
     * 総枠が 1 日ぶん過大になり、退職月の時間外が計上されなくなる。
     * UT-BR05-12（月次清算）と同じ数字であることを、ここで固定する。
     */
    @Test
    @DisplayName("UT-WR-17 9/20 退職の最終月は [2026-09-01, 2026-09-21) の 20 日")
    void midMonthRetirement() {
        var employment = DateRange.closed(LocalDate.of(2020, 4, 1), LocalDate.of(2026, 9, 20));
        var period = SettlementPeriod.of(YearMonth.of(2026, 9), employment).orElseThrow();

        assertThat(period.period()).isEqualTo(
                new DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 21)));
        assertThat(period.days()).isEqualTo(20);
        // UT-BR05-12: 20 ÷ 7 × 2,400 = 6,857.14… → 分未満切り捨てで 6,857 分
        assertThat(period.statutoryTotalLimit(WEEKLY)).isEqualTo(Duration.ofMinutes(6_857));
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
    @DisplayName("UT-WR-18 2026-06 は所定総（22 日 × 8 時間）が法定総枠を超えて警告が返る")
    void scheduleCanExceedTheLimit() {
        var june = SettlementPeriod.of(YearMonth.of(2026, 6),
                DateRange.startingAt(LocalDate.of(2020, 4, 1))).orElseThrow();

        var warning = june.checkCapacity(flex(), 22, WEEKLY);

        assertThat(warning).isPresent();
        assertThat(warning.orElseThrow().scheduled()).isEqualTo(Duration.ofMinutes(10_560));
        assertThat(warning.orElseThrow().limit()).isEqualTo(Duration.ofMinutes(10_285));
        assertThat(warning.orElseThrow().excess()).isEqualTo(Duration.ofMinutes(275));
        assertThat(warning.orElseThrow().month()).isEqualTo(YearMonth.of(2026, 6));
    }

    @Test
    @DisplayName("UT-WR-19 所定総が総枠を超えない月では警告は返らない")
    void scheduleWithinTheLimit() {
        var may = SettlementPeriod.of(YearMonth.of(2026, 5),
                DateRange.startingAt(LocalDate.of(2020, 4, 1))).orElseThrow();

        assertThat(may.checkCapacity(flex(), 20, WEEKLY)).isEmpty();
    }

    /**
     * 総枠ちょうどは<strong>超えていない</strong>。ここで警告を立てると、
     * 適法な設定に対して人事へ無用の警告を出し続けることになる。
     */
    @Test
    @DisplayName("所定総が総枠ちょうどでは警告は返らない")
    void scheduleExactlyAtTheLimit() {
        var june = SettlementPeriod.of(YearMonth.of(2026, 6),
                DateRange.startingAt(LocalDate.of(2020, 4, 1))).orElseThrow();
        Duration limit = june.statutoryTotalLimit(WEEKLY);
        var exact = new FlextimeSystem(flex().flexibleTime(), flex().coreTime(), limit);

        assertThat(june.checkCapacity(exact, 1, WEEKLY)).isEmpty();
    }

    /**
     * 警告は「超えている」ことを表す型なので、
     * <strong>超えていない値では作れない。</strong>
     */
    @Test
    @DisplayName("超えていない値では警告を作れない")
    void warningCannotBeBuiltWithoutExcess() {
        assertThatThrownBy(() -> new ScheduleExceedsStatutoryLimit(
                YearMonth.of(2026, 6), Duration.ofMinutes(100), Duration.ofMinutes(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("総枠を超えていないのに警告を作ろうとしています");
    }

    /**
     * 清算期間は<strong>対象月の内側</strong>に収まらなければならない。
     * はみ出したまま総枠を計算すると、暦日数が過大になり時間外が計上されなくなる。
     */
    @Test
    @DisplayName("対象月からはみ出す清算期間は作れない")
    void periodMustStayInsideTheMonth() {
        assertThatThrownBy(() -> new SettlementPeriod(YearMonth.of(2026, 6),
                new DateRange(LocalDate.of(2026, 5, 25), LocalDate.of(2026, 7, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("清算期間は対象月の内側である必要があります");

        assertThatThrownBy(() -> new SettlementPeriod(YearMonth.of(2026, 6),
                new DateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("清算期間は対象月の内側である必要があります");
    }

    /** 週法定労働時間を 0 にすると総枠が 0 になり、全時間が時間外になる。 */
    @Test
    @DisplayName("週法定労働時間が 0 の総枠は求められない")
    void zeroWeeklyIsRejected() {
        var june = SettlementPeriod.of(YearMonth.of(2026, 6),
                DateRange.startingAt(LocalDate.of(2020, 4, 1))).orElseThrow();

        assertThatThrownBy(() -> june.statutoryTotalLimit(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("週法定労働時間は正である必要があります");
    }

    /** 2 月は 28 日と 29 日で総枠が変わる。閏年を固定値で書かない。 */
    @Test
    @DisplayName("閏年の 2 月は総枠が 1 日ぶん増える")
    void leapFebruaryHasALargerLimit() {
        var leap = SettlementPeriod.of(YearMonth.of(2028, 2),
                DateRange.startingAt(LocalDate.of(2020, 4, 1))).orElseThrow();
        var common = SettlementPeriod.of(YearMonth.of(2026, 2),
                DateRange.startingAt(LocalDate.of(2020, 4, 1))).orElseThrow();

        assertThat(leap.days()).isEqualTo(29);
        assertThat(common.days()).isEqualTo(28);
        // 29 ÷ 7 × 2,400 = 9,942 分 / 28 ÷ 7 × 2,400 = 9,600 分
        assertThat(leap.statutoryTotalLimit(WEEKLY)).isEqualTo(Duration.ofMinutes(9_942));
        assertThat(common.statutoryTotalLimit(WEEKLY)).isEqualTo(Duration.ofMinutes(9_600));
    }
}
