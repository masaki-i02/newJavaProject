package jp.co.sample.kintai.payroll.infrastructure;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.payroll.domain.ExclusionReason;
import jp.co.sample.kintai.payroll.domain.PayrollExport;
import jp.co.sample.kintai.payroll.domain.PayrollExportId;
import jp.co.sample.kintai.payroll.domain.PayrollExportRepository;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.AnnualScheduledHours;

/**
 * {@link PayrollExportRepository} の実装。
 *
 * <p>追記専用の記録なので {@code JdbcTemplate} を使う。
 * 同一性の管理も変更の追跡も要らない（CLAUDE.md 2.2）。
 *
 * <p><strong>実行日時を渡さない。</strong> DB の {@code now()} が打つ。
 * 渡せる形にすると、監査の時刻を実行者が決められる。
 */
@Repository
class PayrollExportRepositoryAdapter implements PayrollExportRepository {

    private final JdbcTemplate jdbc;

    PayrollExportRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PayrollExport export) {
        jdbc.update("""
                INSERT INTO payroll_exports
                       (id, target_month, exported_by, fiscal_year,
                        annual_scheduled_days, annual_scheduled_minutes,
                        monthly_average_minutes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                export.id().value(), export.month().atDay(1),
                export.exportedBy().value(), export.divisor().fiscalYear(),
                export.divisor().scheduledDays(),
                export.divisor().annualTotal().toMinutes(),
                export.monthlyAverageMinutes());

        // ★ 対象社員をまとめて入れる。1 件ずつ入れると社員数ぶんの往復になる
        List<Object[]> rows = new ArrayList<>();
        export.targets().forEach((employeeId, reason) -> rows.add(new Object[] {
                export.id().value(), employeeId.value(),
                reason.map(Enum::name).orElse(null)}));
        jdbc.batchUpdate("""
                INSERT INTO payroll_export_targets (export_id, employee_id, excluded_reason)
                VALUES (?, ?, ?)
                """, rows);
    }

    @Override
    public Optional<PayrollExport> find(PayrollExportId id) {
        List<PayrollExport> found = jdbc.query(SELECT + " WHERE id = ?",
                this::toExport, id.value());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withTargets(found.get(0)));
    }

    @Override
    public List<PayrollExport> findByMonth(Optional<YearMonth> month, int limit) {
        // ★ 監査は「最後に出したのはいつか」から見る
        List<PayrollExport> found = month
                .map(target -> jdbc.query(
                        SELECT + " WHERE target_month = ? ORDER BY exported_at DESC LIMIT ?",
                        this::toExport, target.atDay(1), limit))
                .orElseGet(() -> jdbc.query(
                        SELECT + " ORDER BY exported_at DESC LIMIT ?", this::toExport, limit));
        return found.stream().map(this::withTargets).toList();
    }

    private static final String SELECT = """
            SELECT id, target_month, exported_by, exported_at, fiscal_year,
                   annual_scheduled_days, annual_scheduled_minutes
              FROM payroll_exports
            """;

    private PayrollExport toExport(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new PayrollExport(
                new PayrollExportId(rs.getObject("id", UUID.class)),
                YearMonth.from(rs.getObject("target_month", LocalDate.class)),
                new EmployeeId(rs.getObject("exported_by", UUID.class)),
                Optional.of(BusinessZone.toLocal(
                        rs.getObject("exported_at", OffsetDateTime.class))),
                AnnualScheduledHours.of(rs.getInt("fiscal_year"),
                        rs.getInt("annual_scheduled_days"),
                        Duration.ofMinutes(rs.getInt("annual_scheduled_minutes"))),
                Map.of());
    }

    /**
     * 対象社員を読み足す。
     *
     * <p><strong>並び順を固定する。</strong> 記録の照会と CSV の作り直しで
     * 順序が変わると、同じ記録から違う順の CSV が出る。
     */
    private PayrollExport withTargets(PayrollExport export) {
        Map<EmployeeId, Optional<ExclusionReason>> targets = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.employee_id, t.excluded_reason
                  FROM payroll_export_targets t
                  JOIN employees e ON e.id = t.employee_id
                 WHERE t.export_id = ?
                 ORDER BY e.employee_number, e.hired_on
                """, rs -> {
            targets.put(new EmployeeId(rs.getObject("employee_id", UUID.class)),
                    Optional.ofNullable(rs.getString("excluded_reason"))
                            .map(ExclusionReason::valueOf));
        }, export.id().value());
        return new PayrollExport(export.id(), export.month(), export.exportedBy(),
                export.exportedAt(), export.divisor(), targets);
    }
}
