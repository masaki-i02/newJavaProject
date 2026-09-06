package jp.co.sample.kintai.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.AnnualScheduledHours;

/**
 * 出力の記録の不変条件（UT-PAY-31〜33・BR-18）。
 *
 * <p><strong>集約に直接あてる。</strong>
 * ここの検査は {@code PayrollExportService.export} が
 * 対象月から分母の年度を導いているので、<strong>API のテストからは一度も働かない</strong>
 * （CLAUDE.md 落とし穴 58）。経路外のために残す検査は、経路外から確かめる。
 */
@DisplayName("給与連携の出力の記録（BR-18）")
class PayrollExportTest {

    private static final YearMonth MAY = YearMonth.of(2026, 5);
    private static final EmployeeId HR = new EmployeeId(UUID.randomUUID());

    /** 2026 年度・261 日 × 480 分。月平均は 10,440 分。 */
    private static AnnualScheduledHours divisorOf(int fiscalYear) {
        return AnnualScheduledHours.of(fiscalYear, 261, Duration.ofMinutes(125_280));
    }

    /**
     * <strong>対象月と分母の年度が食い違うと、支払の根拠にならない。</strong>
     * 2026 年 5 月は 2026 年度なので、2025 年度の分母を当てるのは誤りである。
     */
    @Test
    @DisplayName("UT-PAY-31 対象月と分母の年度が食い違うと生成できない")
    void divisorMustBelongToTheSameFiscalYear() {
        assertThatThrownBy(() -> PayrollExport.of(MAY, HR, divisorOf(2025), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("対象月と分母の年度が食い違っています");
    }

    /** 1 月〜3 月は前の年の年度である。ここは食い違いではない。 */
    @Test
    @DisplayName("UT-PAY-32 3 月分には前の年の年度の分母が当たる")
    void marchBelongsToThePreviousFiscalYear() {
        PayrollExport export = PayrollExport.of(YearMonth.of(2027, 3), HR,
                divisorOf(2026), Map.of());

        assertThat(export.divisor().fiscalYear()).isEqualTo(2026);
    }

    /**
     * <strong>反復順序を保つ。</strong>
     * {@code Map.copyOf} は順序を保証しないので、
     * 同じ記録から作り直した CSV の行の順序が変わりうる。
     */
    @Test
    @DisplayName("UT-PAY-33 対象社員の反復順序は渡した順のまま保たれる")
    void targetsKeepTheirOrder() {
        Map<EmployeeId, Optional<ExclusionReason>> targets = new LinkedHashMap<>();
        EmployeeId first = new EmployeeId(UUID.randomUUID());
        EmployeeId second = new EmployeeId(UUID.randomUUID());
        EmployeeId third = new EmployeeId(UUID.randomUUID());
        targets.put(first, Optional.empty());
        targets.put(second, Optional.of(ExclusionReason.NOT_CLOSED));
        targets.put(third, Optional.empty());

        PayrollExport export = PayrollExport.of(MAY, HR, divisorOf(2026), targets);

        assertThat(export.targets().keySet()).containsExactly(first, second, third);
        assertThat(export.includedEmployeeIds()).containsExactly(first, third);
        assertThat(export.rowCount()).isEqualTo(2);
    }
}
