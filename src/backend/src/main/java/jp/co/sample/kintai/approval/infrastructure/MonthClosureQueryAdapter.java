package jp.co.sample.kintai.approval.infrastructure;

import java.time.YearMonth;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.MonthClosureQuery;

/**
 * {@link MonthClosureQuery} の実装。
 *
 * <p><strong>締め状態を持つのは {@code approval} なので、実装もここに置く</strong>（ADR 0004）。
 * 各コンテキストが独自に締め状態を持つと、必ず食い違う。
 *
 * <p><strong>行が無い月は「下書き」として扱う。</strong>
 * 月次勤怠の行は提出のときに作られるので、
 * まだ何もしていない月には行が存在しない。行の不在を「締め済み」と読むと、
 * <strong>入社直後の社員が 1 度も打刻できなくなる。</strong>
 */
@Repository
class MonthClosureQueryAdapter implements MonthClosureQuery {

    private final JdbcTemplate jdbc;

    MonthClosureQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isClosed(EmployeeId employeeId, YearMonth month) {
        return "CLOSED".equals(statusOf(employeeId, month));
    }

    @Override
    public boolean acceptsTimeClock(EmployeeId employeeId, YearMonth month) {
        // 下書きだけ。提出済みは承認者が見ている内容を勝手に変えないために塞ぐ
        return "DRAFT".equals(statusOf(employeeId, month));
    }

    @Override
    public boolean acceptsCorrectionRequest(EmployeeId employeeId, YearMonth month) {
        String status = statusOf(employeeId, month);
        return "DRAFT".equals(status) || "SUBMITTED".equals(status);
    }

    /** その月の状態。行が無ければ下書き。 */
    private String statusOf(EmployeeId employeeId, YearMonth month) {
        List<String> found = jdbc.queryForList("""
                SELECT status FROM monthly_attendances
                WHERE employee_id = ? AND target_month = ?
                """, String.class, employeeId.value(), month.atDay(1));
        return found.isEmpty() ? "DRAFT" : found.getFirst();
    }
}
