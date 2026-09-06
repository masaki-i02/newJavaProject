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

import jp.co.sample.kintai.leave.domain.AttendanceRate;
import jp.co.sample.kintai.leave.domain.GrantDecision;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantId;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantRepository;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * {@link PaidLeaveGrantRepository} の実装。
 *
 * <p>付与は追記が主で、更新するのは再判定のときだけである。
 * 同一性の管理も変更の追跡も要らないので {@code JdbcTemplate} を使う。
 */
@Repository
class PaidLeaveGrantRepositoryAdapter implements PaidLeaveGrantRepository {

    private final JdbcTemplate jdbc;

    PaidLeaveGrantRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PaidLeaveGrant grant) {
        jdbc.update("""
                INSERT INTO paid_leave_grants (id, employee_id, grant_index, granted_on,
                        granted, days, total_working_days, attended_days,
                        deemed_attended_days, deemed_reason, assessed_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """,
                grant.id().value(), grant.employeeId().value(), grant.grantIndex(),
                grant.grantedOn(), grant.isGranted(),
                grant.isGranted() ? grant.days() : null,
                grant.rate().totalWorkingDays(), grant.rate().attendedDays(),
                grant.rate().deemedAttendedDays(), grant.rate().deemedReason(),
                BusinessZone.toAbsolute(grant.assessedAt()));
    }

    /**
     * 再判定の結果で上書きする。
     *
     * <p><strong>版を SQL の {@code WHERE} で突き合わせる。</strong>
     * 読んでから比べる形にすると、読みと書きの間に別の再判定が入りうる。
     */
    @Override
    public void update(PaidLeaveGrant grant, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE paid_leave_grants
                   SET granted = ?, days = ?, total_working_days = ?, attended_days = ?,
                       deemed_attended_days = ?, deemed_reason = ?, assessed_at = ?,
                       version = version + 1
                 WHERE id = ? AND version = ?
                """,
                grant.isGranted(), grant.isGranted() ? grant.days() : null,
                grant.rate().totalWorkingDays(), grant.rate().attendedDays(),
                grant.rate().deemedAttendedDays(), grant.rate().deemedReason(),
                BusinessZone.toAbsolute(grant.assessedAt()),
                grant.id().value(), expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockingFailureException(
                    "付与の版が一致しません: 期待 " + expectedVersion);
        }
    }

    @Override
    public Optional<PaidLeaveGrant> find(EmployeeId employeeId, LocalDate grantedOn) {
        return jdbc.query(SELECT + " WHERE employee_id = ? AND granted_on = ?",
                        this::toGrant, employeeId.value(), grantedOn)
                .stream().findFirst();
    }

    /**
     * その社員の付与を古い順に読む。
     *
     * <p><strong>失効しているかどうかで絞らない。</strong>
     * 判定は {@link PaidLeaveGrant#validPeriod()} が行う。
     * SQL に {@code granted_on > asOf - interval '2 years'} と書くと、
     * 日付演算のクランプがあるためドメインと 1 日ずれる（CLAUDE.md 落とし穴 91）。
     */
    @Override
    public List<PaidLeaveGrant> findAll(EmployeeId employeeId) {
        return jdbc.query(SELECT + " WHERE employee_id = ? ORDER BY granted_on",
                this::toGrant, employeeId.value());
    }

    @Override
    public List<PaidLeaveGrant> findGrantedFor(List<EmployeeId> employeeIds) {
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        UUID[] ids = employeeIds.stream().map(EmployeeId::value).toArray(UUID[]::new);
        return jdbc.query(SELECT + " WHERE employee_id = ANY(?) ORDER BY employee_id,"
                        + " granted_on", this::toGrant, (Object) ids);
    }

    private static final String SELECT = """
            SELECT id, employee_id, grant_index, granted_on, granted, days,
                   total_working_days, attended_days, deemed_attended_days,
                   deemed_reason, assessed_at, version
              FROM paid_leave_grants
            """;

    private PaidLeaveGrant toGrant(ResultSet rs, int rowNum) throws SQLException {
        boolean granted = rs.getBoolean("granted");
        // ★ 「不付与なのに日数がある」を型で作れなくしている（GrantDecision が sealed）。
        //   DB の decision_check と一対一で対応する
        GrantDecision decision = granted
                ? new GrantDecision.Granted(rs.getInt("days"))
                : new GrantDecision.Withheld();
        return new PaidLeaveGrant(
                new PaidLeaveGrantId((UUID) rs.getObject("id")),
                new EmployeeId((UUID) rs.getObject("employee_id")),
                rs.getInt("grant_index"),
                rs.getObject("granted_on", LocalDate.class),
                new AttendanceRate(rs.getInt("total_working_days"),
                        rs.getInt("attended_days"), rs.getInt("deemed_attended_days"),
                        rs.getString("deemed_reason")),
                decision,
                BusinessZone.toLocal(rs.getObject("assessed_at", OffsetDateTime.class)),
                rs.getLong("version"));
    }
}
