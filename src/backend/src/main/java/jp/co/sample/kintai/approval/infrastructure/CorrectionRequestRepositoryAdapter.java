package jp.co.sample.kintai.approval.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.approval.domain.CorrectionItem;
import jp.co.sample.kintai.approval.domain.CorrectionRequest;
import jp.co.sample.kintai.approval.domain.CorrectionRequestId;
import jp.co.sample.kintai.approval.domain.CorrectionRequestRepository;
import jp.co.sample.kintai.approval.domain.CorrectionStatus;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEventId;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * {@link CorrectionRequestRepository} の実装。
 *
 * <p>申請とその項目は<strong>1 つのまとまりとして書き、まとめて読む。</strong>
 * 項目だけを別に足せる形にすると、決着済みの申請に項目を追加できてしまう。
 *
 * <p>項目は登録時にしか書かない。決裁では状態と決裁の記録だけが変わる。
 */
@Repository
class CorrectionRequestRepositoryAdapter implements CorrectionRequestRepository {

    private final JdbcTemplate jdbc;

    CorrectionRequestRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CorrectionRequest> find(CorrectionRequestId id) {
        return jdbc.query(SELECT + " WHERE id = ?", this::toRequest, id.value())
                .stream().findFirst();
    }

    @Override
    public Optional<CorrectionRequest> findPending(EmployeeId employeeId,
                                                   LocalDate workDate) {
        return jdbc.query(SELECT
                        + " WHERE employee_id = ? AND work_date = ? AND status = 'SUBMITTED'",
                        this::toRequest, employeeId.value(), workDate)
                .stream().findFirst();
    }

    @Override
    public List<CorrectionRequest> findPending() {
        return jdbc.query(SELECT + " WHERE status = 'SUBMITTED' ORDER BY requested_at",
                this::toRequest);
    }

    @Override
    public List<CorrectionRequest> findByEmployee(EmployeeId employeeId) {
        return jdbc.query(SELECT + " WHERE employee_id = ? ORDER BY work_date DESC,"
                + " requested_at DESC", this::toRequest, employeeId.value());
    }

    @Override
    public void insert(CorrectionRequest request) {
        jdbc.update("""
                INSERT INTO time_clock_correction_requests (id, employee_id, work_date,
                        status, reason, requested_at, version)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                """,
                request.id().value(), request.employeeId().value(), request.workDate(),
                request.status().name(), request.reason(),
                BusinessZone.toAbsolute(request.requestedAt()));

        int sequenceNo = 0;
        for (CorrectionItem item : request.items()) {
            insertItem(request, item, sequenceNo++);
        }
    }

    /**
     * 決裁の結果を保存する。
     *
     * <p><strong>版を SQL の {@code WHERE} で突き合わせる。</strong>
     * 読んでから比べる形にすると、読みと書きの間に他の決裁が入りうる。
     * 更新できた行数が 0 なら競合である。
     */
    @Override
    public void update(CorrectionRequest request, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE time_clock_correction_requests
                   SET status = ?, decided_by = ?, decided_at = ?, decision_comment = ?,
                       version = version + 1
                 WHERE id = ? AND version = ?
                """,
                request.status().name(),
                request.decidedBy().map(EmployeeId::value).orElse(null),
                request.decidedAt().map(BusinessZone::toAbsolute).orElse(null),
                request.decisionComment().orElse(null),
                request.id().value(), expectedVersion);
        if (updated == 0) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "訂正申請の版が一致しません: 期待 " + expectedVersion);
        }
    }

    @Override
    public long currentVersion(CorrectionRequestId id) {
        List<Long> found = jdbc.queryForList(
                "SELECT version FROM time_clock_correction_requests WHERE id = ?",
                Long.class, id.value());
        return found.isEmpty() ? 0L : found.getFirst();
    }

    private void insertItem(CorrectionRequest request, CorrectionItem item,
                            int sequenceNo) {
        switch (item) {
            case CorrectionItem.Revoke revoke -> jdbc.update("""
                    INSERT INTO time_clock_correction_items (id, request_id, sequence_no,
                            action, work_date, target_event_id)
                    VALUES (?, ?, ?, 'REVOKE', ?, ?)
                    """,
                    UUID.randomUUID(), request.id().value(), sequenceNo,
                    request.workDate(), revoke.targetId().value());
            case CorrectionItem.Add add -> jdbc.update("""
                    INSERT INTO time_clock_correction_items (id, request_id, sequence_no,
                            action, work_date, event_type, occurred_at)
                    VALUES (?, ?, ?, 'ADD', ?, ?, ?)
                    """,
                    UUID.randomUUID(), request.id().value(), sequenceNo,
                    request.workDate(), add.event().type().name(),
                    BusinessZone.toAbsolute(add.occurredAt()));
        }
    }

    private static final String SELECT = """
            SELECT id, employee_id, work_date, status, reason, requested_at,
                   decided_by, decided_at, decision_comment
              FROM time_clock_correction_requests
            """;

    private CorrectionRequest toRequest(ResultSet rs, int rowNum) throws SQLException {
        var id = new CorrectionRequestId((UUID) rs.getObject("id"));
        return new CorrectionRequest(id,
                new EmployeeId((UUID) rs.getObject("employee_id")),
                rs.getObject("work_date", LocalDate.class),
                findItems(id),
                rs.getString("reason"),
                CorrectionStatus.valueOf(rs.getString("status")),
                local(rs, "requested_at").orElseThrow(),
                Optional.ofNullable((UUID) rs.getObject("decided_by")).map(EmployeeId::new),
                local(rs, "decided_at"),
                Optional.ofNullable(rs.getString("decision_comment")));
    }

    private List<CorrectionItem> findItems(CorrectionRequestId requestId) {
        return jdbc.query("""
                SELECT action, target_event_id, event_type, occurred_at
                  FROM time_clock_correction_items
                 WHERE request_id = ?
                 ORDER BY sequence_no
                """, (rs, rowNum) -> toItem(rs), requestId.value());
    }

    /**
     * 項目を組み立てる。
     *
     * <p><strong>{@code default} 句を書かない。</strong>
     * 操作を足したときに、ここが最後まで残って気づけなくなるのを防ぐ。
     */
    private static CorrectionItem toItem(ResultSet rs) throws SQLException {
        String action = rs.getString("action");
        return switch (action) {
            case "REVOKE" -> new CorrectionItem.Revoke(
                    new TimeClockEventId((UUID) rs.getObject("target_event_id")));
            case "ADD" -> new CorrectionItem.Add(
                    TimeClockEvent.Type.valueOf(rs.getString("event_type"))
                            .at(BusinessZone.toLocal(
                                    rs.getObject("occurred_at", OffsetDateTime.class))));
            default -> throw new IllegalStateException("未知の訂正の操作です: " + action);
        };
    }

    private static Optional<LocalDateTime> local(ResultSet rs, String column)
            throws SQLException {
        return Optional.ofNullable(rs.getObject(column, OffsetDateTime.class))
                .map(BusinessZone::toLocal);
    }
}
