package jp.co.sample.kintai.payroll.infrastructure;

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

    @Override
    public boolean hasExportUsing(int fiscalYear) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM payroll_exports WHERE fiscal_year = ?)",
                Boolean.class, fiscalYear));
    }
}
