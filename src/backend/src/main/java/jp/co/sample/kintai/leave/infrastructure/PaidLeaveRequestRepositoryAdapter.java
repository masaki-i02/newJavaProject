package jp.co.sample.kintai.leave.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.leave.domain.LeaveRequestEvent;
import jp.co.sample.kintai.leave.domain.LeaveRequestStatus;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantId;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequest;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequestId;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequestRepository;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/** {@link PaidLeaveRequestRepository} の実装。 */
@Repository
class PaidLeaveRequestRepositoryAdapter implements PaidLeaveRequestRepository {

    /** 遷移元が無い（行が作られる）ことを証跡テーブルで表す値。 */
    private static final String NO_PREVIOUS_STATUS = "NONE";

    private final JdbcTemplate jdbc;

    PaidLeaveRequestRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PaidLeaveRequest request) {
        jdbc.update("""
                INSERT INTO paid_leave_requests (id, employee_id, leave_date, reason,
                        status, requested_at, version)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                """,
                request.id().value(), request.employeeId().value(), request.leaveDate(),
                request.reason().orElse(null), request.status().name(),
                BusinessZone.toAbsolute(request.requestedAt()));
    }

    /**
     * 決裁・取消の結果を保存する。
     *
     * <p><strong>配分先も社員 ID とともに書く。</strong>
     * 複合外部キー {@code (grant_id, employee_id)} が
     * 「他人の付与から自分の年休を消化する行」を拒む（DB設計書 3.2）。
     * 単純な {@code grant_id} への外部キーだと、その行を作れてしまう。
     *
     * <p><strong>版を SQL の {@code WHERE} で突き合わせる。</strong>
     */
    @Override
    public void update(PaidLeaveRequest request, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE paid_leave_requests
                   SET status = ?, grant_id = ?, decided_by = ?, decided_at = ?,
                       comment = ?, canceled_by = ?, canceled_at = ?,
                       version = version + 1
                 WHERE id = ? AND version = ?
                """,
                request.status().name(),
                request.grantId().map(PaidLeaveGrantId::value).orElse(null),
                request.decidedBy().map(EmployeeId::value).orElse(null),
                request.decidedAt().map(BusinessZone::toAbsolute).orElse(null),
                request.comment().orElse(null),
                request.canceledBy().map(EmployeeId::value).orElse(null),
                request.canceledAt().map(BusinessZone::toAbsolute).orElse(null),
                request.id().value(), expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockingFailureException(
                    "年休の申請の版が一致しません: 期待 " + expectedVersion);
        }
    }

    @Override
    public Optional<PaidLeaveRequest> find(PaidLeaveRequestId id) {
        return jdbc.query(SELECT + " WHERE id = ?", this::toRequest, id.value())
                .stream().findFirst();
    }

    /**
     * その社員の申請をすべて読む。
     *
     * <p>残日数の計算には承認済みだけでなく<strong>未処理も要る</strong>（BR-16）。
     * 承認済みだけを引くと、残 1 日に対して 2 件の申請が同時に通る。
     */
    @Override
    public List<PaidLeaveRequest> findByEmployee(EmployeeId employeeId) {
        return jdbc.query(SELECT + " WHERE employee_id = ? ORDER BY leave_date,"
                + " requested_at", this::toRequest, employeeId.value());
    }

    @Override
    public List<LocalDate> findApprovedDates(EmployeeId employeeId, DateRange period) {
        return jdbc.queryForList("""
                SELECT leave_date
                  FROM paid_leave_requests
                 WHERE employee_id = ?
                   AND status = 'APPROVED'
                   AND leave_date >= ?
                   AND leave_date <  ?
                 ORDER BY leave_date
                """, LocalDate.class,
                employeeId.value(), period.from(), period.toExclusive());
    }

    @Override
    public long currentVersion(PaidLeaveRequestId id) {
        List<Long> found = jdbc.queryForList(
                "SELECT version FROM paid_leave_requests WHERE id = ?",
                Long.class, id.value());
        return found.isEmpty() ? 0L : found.getFirst();
    }

    @Override
    public List<PaidLeaveRequest> findPending() {
        return jdbc.query(SELECT + " WHERE status = 'SUBMITTED'"
                + " ORDER BY leave_date, requested_at", this::toRequest);
    }

    @Override
    public void appendEvent(LeaveRequestEvent event) {
        jdbc.update("""
                INSERT INTO paid_leave_request_events (id, paid_leave_request_id,
                        from_status, to_status, event_kind, actor_id, comment, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.id(), event.requestId().value(),
                event.fromStatus().map(Enum::name).orElse(NO_PREVIOUS_STATUS),
                event.toStatus().name(), event.kind().name(), event.actorId().value(),
                event.comment().orElse(null),
                BusinessZone.toAbsolute(event.occurredAt()));
    }

    private static final String SELECT = """
            SELECT id, employee_id, leave_date, reason, status, requested_at, grant_id,
                   decided_by, decided_at, comment, canceled_by, canceled_at, version
              FROM paid_leave_requests
            """;

    private PaidLeaveRequest toRequest(ResultSet rs, int rowNum) throws SQLException {
        return new PaidLeaveRequest(
                new PaidLeaveRequestId((UUID) rs.getObject("id")),
                new EmployeeId((UUID) rs.getObject("employee_id")),
                rs.getObject("leave_date", LocalDate.class),
                Optional.ofNullable(rs.getString("reason")),
                LeaveRequestStatus.valueOf(rs.getString("status")),
                local(rs, "requested_at").orElseThrow(),
                Optional.ofNullable((UUID) rs.getObject("grant_id"))
                        .map(PaidLeaveGrantId::new),
                Optional.ofNullable((UUID) rs.getObject("decided_by")).map(EmployeeId::new),
                local(rs, "decided_at"),
                Optional.ofNullable(rs.getString("comment")),
                Optional.ofNullable((UUID) rs.getObject("canceled_by")).map(EmployeeId::new),
                local(rs, "canceled_at"),
                rs.getLong("version"));
    }

    private static Optional<java.time.LocalDateTime> local(ResultSet rs, String column)
            throws SQLException {
        return Optional.ofNullable(rs.getObject(column, OffsetDateTime.class))
                .map(BusinessZone::toLocal);
    }
}
