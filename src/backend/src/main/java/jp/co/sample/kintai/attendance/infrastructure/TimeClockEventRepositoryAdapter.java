package jp.co.sample.kintai.attendance.infrastructure;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEventRepository;
import jp.co.sample.kintai.attendance.domain.TimeClockSequence;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * {@link TimeClockEventRepository} の実装。
 *
 * <p><strong>JPA ではなく {@code JdbcTemplate} を使う</strong>（DB設計書 未決事項 #2 の判断）。
 * この表はレンジパーティションで主キーが {@code (work_date, id)} の複合であり、
 * かつ追記しかしない。O/R マッパーが引き受ける「同一性の管理」「変更の追跡」が
 * まったく要らないので、素の SQL のほうが意図に近い。
 *
 * <p>時刻は {@code timestamptz}（絶対時刻）で保存し、
 * ドメインの壁掛け時計時刻との変換は {@link BusinessZone} に集約する
 * （アーキテクチャ設計書 6.3）。ここで直接ゾーン変換を書かない。
 */
@Repository
class TimeClockEventRepositoryAdapter implements TimeClockEventRepository {

    private final JdbcTemplate jdbc;

    TimeClockEventRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(EmployeeId employeeId, LocalDate workDate, TimeClockEvent event,
                       EmployeeId recordedBy) {
        jdbc.update("""
                INSERT INTO time_clock_events (id, work_date, employee_id, entry_type,
                        event_type, occurred_at, source, recorded_by)
                VALUES (?, ?, ?, 'ENTRY', ?, ?, 'WEB', ?)
                """,
                UUID.randomUUID(), workDate, employeeId.value(), event.type().name(),
                BusinessZone.toAbsolute(event.occurredAt()), recordedBy.value());
    }

    /**
     * その勤務日の有効な打刻列。
     *
     * <p><strong>取り消された打刻を除く。</strong>
     * 取消行そのもの（{@code REVOCATION}）も労働時間の計算には現れない。
     * 除外を忘れると、訂正したはずの打刻が生きたまま二重に数えられる。
     */
    @Override
    public TimeClockSequence findByWorkDate(EmployeeId employeeId, LocalDate workDate) {
        List<TimeClockEvent> events = jdbc.query("""
                SELECT e.event_type, e.occurred_at
                  FROM time_clock_events e
                 WHERE e.work_date = ? AND e.employee_id = ?
                   AND e.entry_type = 'ENTRY'
                   AND NOT EXISTS (
                       SELECT 1 FROM time_clock_events r
                        WHERE r.work_date = e.work_date
                          AND r.employee_id = e.employee_id
                          AND r.entry_type = 'REVOCATION'
                          AND r.revokes_event_id = e.id)
                 ORDER BY e.occurred_at
                """,
                (rs, rowNum) -> toEvent(rs.getString("event_type"),
                        BusinessZone.toLocal(rs.getObject("occurred_at",
                                java.time.OffsetDateTime.class))),
                workDate, employeeId.value());
        return TimeClockSequence.of(events);
    }

    /**
     * まだ退勤していない勤務日。
     *
     * <p>候補は「打刻がある勤務日」だけなので、新しい順に見て
     * <strong>最初に見つかった未完了の日</strong>を返す。
     * 状態機械の判定はドメイン（{@code isClosed}）に任せる。
     * SQL で「退勤があるか」を書くと、状態機械の実装が 2 か所に散る。
     */
    @Override
    public Optional<LocalDate> findOpenWorkDate(EmployeeId employeeId, LocalDate onOrAfter) {
        List<LocalDate> candidates = jdbc.queryForList("""
                SELECT DISTINCT work_date
                  FROM time_clock_events
                 WHERE employee_id = ? AND work_date >= ?
                 ORDER BY work_date DESC
                """, LocalDate.class, employeeId.value(), onOrAfter);
        return candidates.stream()
                .map(workDate -> java.util.Map.entry(workDate,
                        findByWorkDate(employeeId, workDate)))
                .filter(entry -> !entry.getValue().isEmpty())
                .filter(entry -> !entry.getValue().isClosed())
                .map(java.util.Map.Entry::getKey)
                .findFirst();
    }

    /**
     * その期間に<strong>有効な</strong>打刻がある勤務日。
     *
     * <p>取り消された打刻しか無い日は含めない。訂正で全部の打刻が取り消された日は、
     * 「打刻が無い日」と同じ扱いでよい。
     */
    @Override
    public List<LocalDate> findWorkDatesWithEvents(EmployeeId employeeId, DateRange period) {
        return jdbc.queryForList("""
                SELECT DISTINCT e.work_date
                  FROM time_clock_events e
                 WHERE e.employee_id = ?
                   AND e.work_date >= ? AND e.work_date < ?
                   AND e.entry_type = 'ENTRY'
                   AND NOT EXISTS (
                       SELECT 1 FROM time_clock_events r
                        WHERE r.work_date = e.work_date
                          AND r.employee_id = e.employee_id
                          AND r.entry_type = 'REVOCATION'
                          AND r.revokes_event_id = e.id)
                 ORDER BY e.work_date
                """, LocalDate.class, employeeId.value(),
                period.from(), period.toExclusive());
    }

    private static TimeClockEvent toEvent(String eventType, LocalDateTime occurredAt) {
        return switch (eventType) {
            case "CLOCK_IN" -> new TimeClockEvent.ClockIn(occurredAt);
            case "CLOCK_OUT" -> new TimeClockEvent.ClockOut(occurredAt);
            case "BREAK_START" -> new TimeClockEvent.BreakStart(occurredAt);
            case "BREAK_END" -> new TimeClockEvent.BreakEnd(occurredAt);
            default -> throw new IllegalStateException(
                    "未知の打刻種別が保存されています: " + eventType);
        };
    }
}
