package jp.co.sample.kintai.payroll.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import jp.co.sample.kintai.support.IntegrationTestBase;

/**
 * 給与連携の記録の制約（IT-PAY-18〜26・52・BR-18）。
 *
 * <p><strong>「拒否された」ではなく「狙った制約で拒否された」ことを確かめる。</strong>
 * 別の制約に先に引っかかると、狙った検証は行われていない（CLAUDE.md 落とし穴 17・25）。
 */
@DisplayName("給与連携の記録の制約（BR-18）")
class PayrollConstraintTest extends IntegrationTestBase {

    private UUID hr;

    @BeforeEach
    void setUpEmployee() {
        hr = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO employees (id, employee_number, name, email, hired_on)
                VALUES (?, 'E0900', '人事 花子', 'hr@example.com', DATE '2020-04-01')
                """, hr);
    }

    @Test
    @DisplayName("IT-PAY-18 対象月に月初日以外を入れると拒否される")
    void targetMonthMustBeFirstDay() {
        assertThatThrownBy(() -> insertExport(LocalDate.of(2026, 5, 15), 261, 125_280))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payroll_exports_month_check");
    }

    /**
     * <strong>入力を 1 つだけ変える</strong>（CLAUDE.md 落とし穴 12）。
     *
     * <p>{@code payroll_exports_average_check} は 3 つの列の連言なので、
     * 3 つ同時に 0 にすると<strong>どの 1 つを削っても同じ制約名で拒否される。</strong>
     * 1 つずつ破って、それぞれが効いていることを確かめる。
     */
    @Test
    @DisplayName("IT-PAY-19 年間の所定労働日数が 0 だと拒否される")
    void annualScheduledDaysMustBePositive() {
        assertThatThrownBy(() -> insertExport(LocalDate.of(2026, 5, 1), 0, 125_280))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payroll_exports_average_check");
    }

    @Test
    @DisplayName("IT-PAY-60 年間の所定労働時間が 0 だと拒否される")
    void annualScheduledMinutesMustBePositive() {
        assertThatThrownBy(() -> insertExport(LocalDate.of(2026, 5, 1), 261, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payroll_exports_average_check");
    }

    /** 月平均だけを 0 にする。導出の検査より先に、正の値であることを求める。 */
    @Test
    @DisplayName("IT-PAY-61 1 か月平均が 0 だと拒否される")
    void monthlyAverageMustBePositive() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payroll_exports (id, target_month, exported_by, fiscal_year,
                        annual_scheduled_days, annual_scheduled_minutes,
                        monthly_average_minutes)
                VALUES (?, DATE '2026-05-01', ?, 2026, 261, 11, 0)
                """, UUID.randomUUID(), hr))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payroll_exports_average_check");
    }

    /**
     * <strong>導出できる値を列として持つなら、食い違いを DB で禁じる</strong>（落とし穴 39）。
     * 月平均だけを残すと、それがどの日数から出たのかを後から言えない。
     */
    @Test
    @DisplayName("IT-PAY-52 月平均が年間の所定 ÷ 12 と食い違うと拒否される")
    void monthlyAverageMustBeDerived() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payroll_exports (id, target_month, exported_by, fiscal_year,
                        annual_scheduled_days, annual_scheduled_minutes,
                        monthly_average_minutes)
                VALUES (?, DATE '2026-05-01', ?, 2026, 261, 125280, 9999)
                """, UUID.randomUUID(), hr))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payroll_exports_average_derivation_check");
    }

    @Test
    @DisplayName("IT-PAY-20 実在しない社員を実行者にすると拒否される")
    void exportedByMustExist() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payroll_exports (id, target_month, exported_by, fiscal_year,
                        annual_scheduled_days, annual_scheduled_minutes,
                        monthly_average_minutes)
                VALUES (?, DATE '2026-05-01', ?, 2026, 261, 125280, 10440)
                """, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payroll_exports_exported_by_fkey");
    }

    /** <strong>再出力は正当である。</strong> 一意にすると再出力そのものができなくなる。 */
    @Test
    @DisplayName("IT-PAY-21 同じ月を 2 回記録できる")
    void sameMonthMayBeExportedTwice() {
        insertExport(LocalDate.of(2026, 5, 1), 261, 125_280);

        assertThatCode(() -> insertExport(LocalDate.of(2026, 5, 1), 261, 125_280))
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payroll_exports WHERE target_month = DATE '2026-05-01'",
                Integer.class)).isEqualTo(2);
    }

    /** 監査の時刻はアプリケーションの時計ではなく DB の時計で打つ。 */
    @Test
    @DisplayName("IT-PAY-22 実行日時を渡さなくても DB の now() が入る")
    void exportedAtIsStampedByTheDatabase() {
        insertExport(LocalDate.of(2026, 5, 1), 261, 125_280);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payroll_exports
                 WHERE exported_at > now() - interval '1 minute'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("IT-PAY-23 実行者が退職しても記録は残る")
    void recordSurvivesRetirement() {
        insertExport(LocalDate.of(2026, 5, 1), 261, 125_280);

        jdbc.update("UPDATE employees SET retired_on = DATE '2026-06-30' WHERE id = ?", hr);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payroll_exports", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("IT-PAY-24 除外の理由に列挙外の値を入れると拒否される")
    void exclusionReasonIsEnumerated() {
        UUID export = insertExport(LocalDate.of(2026, 5, 1), 261, 125_280);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payroll_export_targets (export_id, employee_id, excluded_reason)
                VALUES (?, ?, 'SOMETHING_ELSE')
                """, export, hr))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payroll_export_targets_reason_check");
    }

    @Test
    @DisplayName("IT-PAY-25 同じ社員を同じ記録に 2 回入れられない")
    void targetIsUniquePerExport() {
        UUID export = insertExport(LocalDate.of(2026, 5, 1), 261, 125_280);
        insertTarget(export, hr, null);

        assertThatThrownBy(() -> insertTarget(export, hr, "NOT_CLOSED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payroll_export_targets_pkey");
    }

    @Test
    @DisplayName("IT-PAY-26 記録を消すと対象社員も消える")
    void targetsCascade() {
        UUID export = insertExport(LocalDate.of(2026, 5, 1), 261, 125_280);
        insertTarget(export, hr, null);

        jdbc.update("DELETE FROM payroll_exports WHERE id = ?", export);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payroll_export_targets", Integer.class)).isZero();
    }

    private UUID insertExport(LocalDate targetMonth, int scheduledDays, int annualMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payroll_exports (id, target_month, exported_by, fiscal_year,
                        annual_scheduled_days, annual_scheduled_minutes,
                        monthly_average_minutes)
                VALUES (?, ?, ?, 2026, ?, ?, ?)
                """, id, targetMonth, hr, scheduledDays, annualMinutes, annualMinutes / 12);
        return id;
    }

    private void insertTarget(UUID export, UUID employee, String reason) {
        jdbc.update("""
                INSERT INTO payroll_export_targets (export_id, employee_id, excluded_reason)
                VALUES (?, ?, ?)
                """, export, employee, reason);
    }
}
