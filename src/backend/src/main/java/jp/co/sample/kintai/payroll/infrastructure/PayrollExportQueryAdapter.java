package jp.co.sample.kintai.payroll.infrastructure;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.shared.domain.PayrollExportQuery;

/**
 * {@link PayrollExportQuery} の実装。
 *
 * <p><strong>出力の記録を持つのは {@code payroll} なので、実装もここに置く</strong>（ADR 0004）。
 * {@code workrule} が {@code payroll_exports} を直接引くと、依存図に無い辺が生まれる。
 */
@Repository
class PayrollExportQueryAdapter implements PayrollExportQuery {

    private final JdbcTemplate jdbc;

    PayrollExportQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * <strong>行が出た記録だけを数える。</strong>
     * {@code excluded_reason IS NULL} が「CSV に出た社員」である。
     *
     * <p>同じ年度に複数の記録があっても分母は同じなので、年度ごとに 1 行へ畳む。
     * 違っていれば、その時点で既にこの検査をすり抜けている。
     */
    @Override
    public List<UsedDivisor> usedDivisorsFrom(int fiscalYear) {
        return jdbc.query("""
                SELECT e.fiscal_year, min(e.annual_scheduled_minutes) AS minutes
                  FROM payroll_exports e
                 WHERE e.fiscal_year >= ?
                   AND EXISTS (SELECT 1 FROM payroll_export_targets t
                                WHERE t.export_id = e.id AND t.excluded_reason IS NULL)
                 GROUP BY e.fiscal_year
                 ORDER BY e.fiscal_year
                """,
                (rs, rowNum) -> new UsedDivisor(rs.getInt("fiscal_year"),
                        rs.getLong("minutes")),
                fiscalYear);
    }
}
