package jp.co.sample.kintai.attendance.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.attendance.domain.monthly.AgreementUsage;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlement;
import jp.co.sample.kintai.attendance.domain.monthly.WeeklyOvertimeCharge;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.SettlementPeriod;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * 月次清算の応答が、ドメインの値を<strong>正しい項目へ</strong>写しているか。
 *
 * <p><strong>なぜ要るか。</strong> 応答の 13 項目はすべて同じ型（分の {@code int}）が
 * 隣り合って並んでいる。**2 つ入れ替えてもコンパイルは通る。**
 * ところが応答の項目を突き合わせるテストが 1 件も無く、
 * {@code overtimeMinutes}・{@code shortageMinutes}・{@code coreTimeAbsenceMinutes}・
 * 36 協定の 4 項目は<strong>どのテストも読んでいなかった</strong>（落とし穴 112）。
 *
 * <p>これらは<strong>承認者が承認の前に見る数字</strong>であり、
 * 入れ替わっても画面は動き続ける。時間外と不足が入れ替われば、
 * 残業した月が欠勤の月として承認される。
 *
 * <p><strong>値をすべて違う数にする。</strong> 同じ値を使うと、
 * 入れ替えても気づけない（落とし穴 24）。
 */
@DisplayName("月次清算の応答の項目対応")
class MonthlySettlementResponseTest {

    private static final EmployeeId TARO = new EmployeeId(UUID.randomUUID());
    private static final WorkRuleSeriesId SERIES = new WorkRuleSeriesId(UUID.randomUUID());
    private static final YearMonth MAY = YearMonth.of(2026, 5);

    /**
     * 固定時間制。<strong>日次・週次・繰越・時間外・不足</strong>がすべて違う値になる。
     *
     * <p>固定時間制では時間外と不足が同時に正になりうる（落とし穴 51）ので、
     * この 2 つを 1 つの月で見分けられる。
     */
    @Test
    @DisplayName("UT-ATT-45 固定時間制の応答は 13 項目をすべて別々の項目へ写す")
    void fixedTimeFieldsAreMappedToTheRightNames() {
        // ★ 週ごとの内訳も 3 項目すべて違う値にする。ここも誰も読んでいなかった
        var week = new WeeklyOvertimeCharge(LocalDate.of(2026, 4, 26),
                LocalDate.of(2026, 5, 3),
                Duration.ofMinutes(2400),  // 週の法定内
                Duration.ofMinutes(150),   // 週 40 時間超
                Duration.ofMinutes(120));  // うちこの月に計上した分
        var settlement = new MonthlySettlement(TARO, period(), SERIES,
                WorkingTimeSystemType.FIXED,
                Duration.ofMinutes(9000),   // 実労働
                Duration.ofMinutes(450),    // 法定休日
                Duration.ofMinutes(8550),   // 対象労働（実労働 − 法定休日）
                Duration.ofMinutes(9600),   // 所定総
                Duration.ofMinutes(10628),  // 法定総枠
                Duration.ofMinutes(300),    // 日次の時間外
                Duration.ofMinutes(120),    // 週次の時間外
                Duration.ofMinutes(60),     // 繰越
                Duration.ofMinutes(480),    // 時間外（日次 + 週次 + 繰越）
                Duration.ofMinutes(720),    // 不足（所定総 − 不足 ≤ 実労働 を満たす値）
                Duration.ofMinutes(90),     // 深夜
                Duration.ZERO,              // コアタイム不在（固定時間制は常に 0）
                0, List.of(week),
                AgreementUsage.of(Duration.ofMinutes(480), Duration.ofMinutes(450),
                        Duration.ofMinutes(1500)));

        var response = MonthlySettlementResponse.from(settlement, 7L);

        assertThat(response.month()).isEqualTo("2026-05");
        assertThat(response.version()).isEqualTo(7L);
        assertThat(response.workingMinutes()).as("実労働").isEqualTo(9000);
        assertThat(response.legalHolidayMinutes()).as("法定休日").isEqualTo(450);
        assertThat(response.targetWorkingMinutes()).as("対象労働").isEqualTo(8550);
        assertThat(response.scheduledTotalMinutes()).as("所定総").isEqualTo(9600);
        assertThat(response.statutoryTotalLimitMinutes()).as("法定総枠").isEqualTo(10628);
        assertThat(response.dailyOvertimeMinutes()).as("日次の時間外").isEqualTo(300);
        assertThat(response.weeklyOvertimeMinutes()).as("週次の時間外").isEqualTo(120);
        assertThat(response.carriedOverOvertimeMinutes()).as("繰越").isEqualTo(60);
        assertThat(response.overtimeMinutes()).as("時間外").isEqualTo(480);
        assertThat(response.shortageMinutes()).as("不足").isEqualTo(720);
        assertThat(response.nightMinutes()).as("深夜").isEqualTo(90);
        assertThat(response.coreTimeAbsenceMinutes()).as("固定時間制のコアタイム不在は 0")
                .isZero();

        var breakdown = response.weeklyBreakdown().get(0);
        assertThat(breakdown.weekStart()).isEqualTo(LocalDate.of(2026, 4, 26));
        assertThat(breakdown.weekEnd()).as("応答は閉区間の最終日を返す")
                .isEqualTo(LocalDate.of(2026, 5, 2));
        assertThat(breakdown.statutoryInsideMinutes()).as("週の法定内").isEqualTo(2400);
        assertThat(breakdown.weekOvertimeMinutes()).as("週 40 時間超").isEqualTo(150);
        assertThat(breakdown.chargedMinutes()).as("この月への計上").isEqualTo(120);
    }

    /**
     * 36 協定の 4 項目と、フレックスのコアタイム不在。
     *
     * <p>ここも<strong>値をすべて違う数にする。</strong>
     * 限度時間と年度上限を取り違えると、超過の判定が丸ごと入れ替わる。
     */
    @Test
    @DisplayName("UT-ATT-46 36 協定とコアタイム不在の項目が入れ替わっていない")
    void agreementAndCoreTimeAbsenceAreMappedToTheRightNames() {
        var settlement = new MonthlySettlement(TARO, period(), SERIES,
                WorkingTimeSystemType.FLEX,
                Duration.ofMinutes(9000), Duration.ofMinutes(480),
                Duration.ofMinutes(8520), Duration.ofMinutes(8400),
                Duration.ofMinutes(10628),
                Duration.ZERO, Duration.ZERO, Duration.ZERO,
                Duration.ofMinutes(3660),  // 時間外（60 時間 + 60 分）
                Duration.ZERO,             // 不足（フレックスは同時に正にならない）
                Duration.ofMinutes(90),
                Duration.ofMinutes(35),    // コアタイム不在
                0, List.of(),
                AgreementUsage.of(Duration.ofMinutes(3660), Duration.ofMinutes(480),
                        Duration.ofMinutes(1500)));

        var response = MonthlySettlementResponse.from(settlement, 1L);

        assertThat(response.coreTimeAbsenceMinutes()).as("コアタイム不在").isEqualTo(35);
        // ★ 60 時間超は「時間外 − 3600 分」。他の項目とはっきり違う数にする
        assertThat(response.overtimeOver60Minutes())
                .as("60 時間を超えたぶん（割増 +50%）").isEqualTo(60);
        assertThat(response.agreement().combinedMinutes())
                .as("6 項 2 号の対象（時間外 + 法定休日）").isEqualTo(4140);
        assertThat(response.agreement().monthlyLimitMinutes())
                .as("限度時間（月 45 時間）").isEqualTo(2700);
        assertThat(response.agreement().annualLimitMinutes())
                .as("年度の上限（360 時間）").isEqualTo(21600);
        assertThat(response.agreement().annualUsedBeforeMinutes())
                .as("その月より前の年度累計").isEqualTo(1500);
    }

    private static SettlementPeriod period() {
        return SettlementPeriod.of(MAY,
                jp.co.sample.kintai.shared.domain.DateRange.startingAt(
                        MAY.minusYears(1).atDay(1)))
                .orElseThrow();
    }
}
