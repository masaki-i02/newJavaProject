package jp.co.sample.kintai.approval.infrastructure;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.approval.domain.MonthlyAttendance;
import jp.co.sample.kintai.approval.domain.MonthlyAttendanceId;
import jp.co.sample.kintai.approval.domain.MonthlyAttendanceRepository;
import jp.co.sample.kintai.approval.domain.MonthlyAttendanceStatus;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * {@link MonthlyAttendanceRepository} の実装。
 *
 * <p>{@code JdbcTemplate} を使う。状態ごとに埋まる列が変わる表なので、
 * <strong>まるごと書き直す方が素直</strong>である。
 * 部分更新にすると、下書きへ戻したときに承認者の列が残る。
 *
 * <p>状態と列の対応は DB の {@code monthly_attendances_state_check} が守る。
 * <strong>アプリケーションと DB の 2 か所で同じ不変条件を守る</strong>形になっており、
 * どちらかを直し忘れると片方が拒否する。
 */
@Repository
class MonthlyAttendanceRepositoryAdapter implements MonthlyAttendanceRepository {

    private final JdbcTemplate jdbc;

    MonthlyAttendanceRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MonthlyAttendance> find(EmployeeId employeeId, YearMonth month) {
        return jdbc.query(SELECT + " WHERE employee_id = ? AND target_month = ?",
                        this::toAttendance, employeeId.value(), month.atDay(1))
                .stream().findFirst();
    }

    @Override
    public List<MonthlyAttendance> findSubmitted(YearMonth month) {
        return jdbc.query(SELECT + " WHERE target_month = ? AND status = 'SUBMITTED'"
                + " ORDER BY submitted_at", this::toAttendance, month.atDay(1));
    }

    @Override
    public void save(MonthlyAttendance attendance, long expectedVersion) {
        long actual = currentVersion(attendance.employeeId(), attendance.month());
        if (actual != expectedVersion) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "月次勤怠の版が一致しません: 期待 %d / 現在 %d"
                            .formatted(expectedVersion, actual));
        }
        save(attendance);
    }

    /**
     * 現在の版。<strong>行が無い月は 0</strong> を返す。
     *
     * <p>行を作るときは版を <strong>1</strong> から始める。
     * 0 のまま入れると「行が無い」と「作られたばかり」が同じ値になり、
     * <strong>提出前の版を握った承認者の要求が、提出後にそのまま通る。</strong>
     */
    @Override
    public long currentVersion(EmployeeId employeeId, YearMonth month) {
        List<Long> found = jdbc.queryForList("""
                SELECT version FROM monthly_attendances
                WHERE employee_id = ? AND target_month = ?
                """, Long.class, employeeId.value(), month.atDay(1));
        return found.isEmpty() ? 0L : found.getFirst();
    }

    @Override
    public void save(MonthlyAttendance attendance) {
        var status = attendance.status();
        jdbc.update("""
                INSERT INTO monthly_attendances (id, employee_id, target_month, status,
                        submitted_at, submitted_by, approved_by, approved_at,
                        closed_by, closed_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT (employee_id, target_month) DO UPDATE SET
                        status = EXCLUDED.status,
                        submitted_at = EXCLUDED.submitted_at,
                        submitted_by = EXCLUDED.submitted_by,
                        approved_by = EXCLUDED.approved_by,
                        approved_at = EXCLUDED.approved_at,
                        closed_by = EXCLUDED.closed_by,
                        closed_at = EXCLUDED.closed_at,
                        version = monthly_attendances.version + 1
                """,
                attendance.id().value(), attendance.employeeId().value(),
                attendance.month().atDay(1), status.state().name(),
                timestamp(submittedAt(status)), id(submittedBy(status)),
                id(approvedBy(status)), timestamp(approvedAt(status)),
                id(closedBy(status)), timestamp(closedAt(status)));
    }

    private static final String SELECT = """
            SELECT id, employee_id, target_month, status,
                   submitted_at, submitted_by, approved_by, approved_at,
                   closed_by, closed_at
              FROM monthly_attendances
            """;

    private MonthlyAttendance toAttendance(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        var id = new MonthlyAttendanceId((UUID) rs.getObject("id"));
        var employeeId = new EmployeeId((UUID) rs.getObject("employee_id"));
        var month = YearMonth.from(rs.getObject("target_month", LocalDate.class));
        return new MonthlyAttendance(id, employeeId, month, toStatus(rs));
    }

    /**
     * 状態を組み立てる。
     *
     * <p><strong>{@code default} 句を書かない。</strong>
     * 状態を足したときに、ここが最後まで残って気づけなくなるのを防ぐ。
     * DB 側の {@code CHECK} と対応が崩れたら {@code IllegalStateException} で落とす。
     */
    private MonthlyAttendanceStatus toStatus(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        String status = rs.getString("status");
        return switch (status) {
            case "DRAFT" -> new MonthlyAttendanceStatus.Draft();
            case "SUBMITTED" -> new MonthlyAttendanceStatus.Submitted(
                    employee(rs, "submitted_by"), local(rs, "submitted_at"));
            case "APPROVED" -> new MonthlyAttendanceStatus.Approved(
                    employee(rs, "submitted_by"), local(rs, "submitted_at"),
                    employee(rs, "approved_by"), local(rs, "approved_at"));
            case "CLOSED" -> new MonthlyAttendanceStatus.Closed(
                    employee(rs, "submitted_by"), local(rs, "submitted_at"),
                    employee(rs, "approved_by"), local(rs, "approved_at"),
                    employee(rs, "closed_by"), local(rs, "closed_at"));
            default -> throw new IllegalStateException("未知の状態です: " + status);
        };
    }

    private static EmployeeId employee(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        return new EmployeeId((UUID) rs.getObject(column));
    }

    private static java.time.LocalDateTime local(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        return BusinessZone.toLocal(rs.getObject(column, OffsetDateTime.class));
    }

    private static UUID id(Optional<EmployeeId> employeeId) {
        return employeeId.map(EmployeeId::value).orElse(null);
    }

    private static OffsetDateTime timestamp(Optional<java.time.LocalDateTime> at) {
        return at.map(value -> value.atZone(BusinessZone.ID).toOffsetDateTime())
                .orElse(null);
    }

    private static Optional<EmployeeId> submittedBy(MonthlyAttendanceStatus status) {
        return switch (status) {
            case MonthlyAttendanceStatus.Draft ignored -> Optional.empty();
            case MonthlyAttendanceStatus.Submitted s -> Optional.of(s.submittedBy());
            case MonthlyAttendanceStatus.Approved s -> Optional.of(s.submittedBy());
            case MonthlyAttendanceStatus.Closed s -> Optional.of(s.submittedBy());
        };
    }

    private static Optional<java.time.LocalDateTime> submittedAt(
            MonthlyAttendanceStatus status) {
        return switch (status) {
            case MonthlyAttendanceStatus.Draft ignored -> Optional.empty();
            case MonthlyAttendanceStatus.Submitted s -> Optional.of(s.submittedAt());
            case MonthlyAttendanceStatus.Approved s -> Optional.of(s.submittedAt());
            case MonthlyAttendanceStatus.Closed s -> Optional.of(s.submittedAt());
        };
    }

    private static Optional<EmployeeId> approvedBy(MonthlyAttendanceStatus status) {
        return switch (status) {
            case MonthlyAttendanceStatus.Draft ignored -> Optional.empty();
            case MonthlyAttendanceStatus.Submitted ignored -> Optional.empty();
            case MonthlyAttendanceStatus.Approved s -> Optional.of(s.approvedBy());
            case MonthlyAttendanceStatus.Closed s -> Optional.of(s.approvedBy());
        };
    }

    private static Optional<java.time.LocalDateTime> approvedAt(
            MonthlyAttendanceStatus status) {
        return switch (status) {
            case MonthlyAttendanceStatus.Draft ignored -> Optional.empty();
            case MonthlyAttendanceStatus.Submitted ignored -> Optional.empty();
            case MonthlyAttendanceStatus.Approved s -> Optional.of(s.approvedAt());
            case MonthlyAttendanceStatus.Closed s -> Optional.of(s.approvedAt());
        };
    }

    private static Optional<EmployeeId> closedBy(MonthlyAttendanceStatus status) {
        return status instanceof MonthlyAttendanceStatus.Closed closed
                ? Optional.of(closed.closedBy()) : Optional.empty();
    }

    private static Optional<java.time.LocalDateTime> closedAt(
            MonthlyAttendanceStatus status) {
        return status instanceof MonthlyAttendanceStatus.Closed closed
                ? Optional.of(closed.closedAt()) : Optional.empty();
    }
}
