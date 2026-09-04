package jp.co.sample.kintai.approval.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.approval.domain.ApprovalEvent;
import jp.co.sample.kintai.approval.domain.ApprovalEventKind;
import jp.co.sample.kintai.approval.domain.AttendanceState;
import jp.co.sample.kintai.approval.domain.ApprovalEventRepository;
import jp.co.sample.kintai.approval.domain.MonthlyAttendanceId;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * {@link ApprovalEventRepository} の実装。<strong>追記のみ。</strong>
 *
 * <p>証跡が残すのは<strong>状態の名前だけ</strong>である（{@link AttendanceState}）。
 * その時点の提出者や承認者を再現する列を持たないので、
 * {@code MonthlyAttendanceStatus} では読み戻せない。
 * 無理に埋めると、実際には残っていない情報を作り出すことになる。
 */
@Repository
class ApprovalEventRepositoryAdapter implements ApprovalEventRepository {

    private final JdbcTemplate jdbc;

    ApprovalEventRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(ApprovalEvent event) {
        jdbc.update("""
                INSERT INTO approval_events (id, monthly_attendance_id, from_status,
                        to_status, event_kind, actor_id, comment, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), event.monthlyAttendanceId().value(),
                event.from().name(), event.to().name(), event.kind().name(),
                event.actor().value(), event.comment().orElse(null),
                event.occurredAt().atZone(BusinessZone.ID).toOffsetDateTime());
    }

    @Override
    public List<ApprovalEvent> findBy(MonthlyAttendanceId monthlyAttendanceId) {
        return jdbc.query("""
                SELECT from_status, to_status, event_kind, actor_id, comment, occurred_at
                  FROM approval_events
                 WHERE monthly_attendance_id = ?
                 ORDER BY occurred_at, created_at
                """,
                (rs, rowNum) -> new ApprovalEvent(monthlyAttendanceId,
                        AttendanceState.valueOf(rs.getString("from_status")),
                        AttendanceState.valueOf(rs.getString("to_status")),
                        ApprovalEventKind.valueOf(rs.getString("event_kind")),
                        new EmployeeId((UUID) rs.getObject("actor_id")),
                        Optional.ofNullable(rs.getString("comment")),
                        BusinessZone.toLocal(rs.getObject("occurred_at",
                                OffsetDateTime.class))),
                monthlyAttendanceId.value());
    }
}
