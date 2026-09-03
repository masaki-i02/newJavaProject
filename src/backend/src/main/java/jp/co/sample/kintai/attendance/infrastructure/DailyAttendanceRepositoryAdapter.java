package jp.co.sample.kintai.attendance.infrastructure;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.attendance.domain.BreakTimeRequirement;
import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.attendance.domain.WorkSlice;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.PremiumType;
import jp.co.sample.kintai.shared.domain.TimeRange;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.WorkRuleId;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * {@link DailyAttendanceRepository} の実装。
 *
 * <p>{@code JdbcTemplate} を使う理由は {@link TimeClockEventRepositoryAdapter} と同じで、
 * 内訳を<strong>まるごと入れ替える</strong>書き方が素直だからである。
 * 部分更新にすると、再計算で区間の数が減ったときに古い区間が残り、
 * 内訳の合計が実労働時間と食い違う。
 *
 * <p>{@code premiums} は配列型で持つ。割増が 1 区間に重なりうるためで、
 * 別表へ正規化すると「属性の無い基本時間の区間」を表現できなくなる。
 */
@Repository
class DailyAttendanceRepositoryAdapter implements DailyAttendanceRepository {

    private final JdbcTemplate jdbc;

    DailyAttendanceRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(EmployeeId employeeId, DailyAttendance attendance, WorkRuleId workRuleId) {
        // 再計算なので、同じ社員・同じ勤務日の行は内訳ごと消して入れ直す。
        // ON DELETE CASCADE により slices も消える
        jdbc.update("DELETE FROM daily_attendances WHERE employee_id = ? AND work_date = ?",
                employeeId.value(), attendance.workDate());

        UUID id = UUID.randomUUID();
        boolean breakSatisfied = new BreakTimeRequirement(
                attendance.workingTime(), attendance.breakTime()).isSatisfied();
        jdbc.update("""
                INSERT INTO daily_attendances (id, employee_id, work_date, day_type,
                        working_time_system, work_rule_id, working_minutes, break_minutes,
                        base_minutes, overtime_within_statutory_minutes,
                        overtime_beyond_statutory_minutes, night_minutes,
                        legal_holiday_minutes, break_requirement_satisfied)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, employeeId.value(), attendance.workDate(), attendance.dayType().name(),
                attendance.workingTimeSystem().name(), workRuleId.value(),
                minutes(attendance.workingTime()), minutes(attendance.breakTime()),
                minutes(attendance.baseTime()),
                minutes(attendance.overtimeWithinStatutoryTime()),
                minutes(attendance.overtimeBeyondStatutoryTime()),
                minutes(attendance.nightTime()), minutes(attendance.legalHolidayTime()),
                breakSatisfied);

        int sequenceNo = 1;
        for (WorkSlice slice : attendance.slices()) {
            jdbc.update("""
                    INSERT INTO daily_attendance_slices (id, daily_attendance_id, sequence_no,
                            calendar_date, started_at, ended_at, premiums)
                    VALUES (?, ?, ?, ?, ?, ?, ?::text[])
                    """,
                    UUID.randomUUID(), id, sequenceNo++, slice.calendarDate(),
                    BusinessZone.toAbsolute(slice.range().start()),
                    BusinessZone.toAbsolute(slice.range().end()),
                    toArrayLiteral(slice.premiums()));
        }
    }

    @Override
    public Optional<DailyAttendance> find(EmployeeId employeeId, LocalDate workDate) {
        return findByPeriod(employeeId, new DateRange(workDate, workDate.plusDays(1)))
                .stream().findFirst();
    }

    @Override
    public List<DailyAttendance> findByPeriod(EmployeeId employeeId, DateRange period) {
        List<Row> rows = jdbc.query("""
                SELECT id, work_date, day_type, working_time_system, working_minutes,
                       break_minutes, base_minutes, overtime_within_statutory_minutes,
                       overtime_beyond_statutory_minutes, night_minutes, legal_holiday_minutes
                  FROM daily_attendances
                 WHERE employee_id = ? AND work_date >= ? AND work_date < ?
                 ORDER BY work_date
                """,
                (rs, rowNum) -> new Row(
                        UUID.fromString(rs.getString("id")),
                        rs.getObject("work_date", LocalDate.class),
                        DayType.valueOf(rs.getString("day_type")),
                        WorkingTimeSystemType.valueOf(rs.getString("working_time_system")),
                        Duration.ofMinutes(rs.getInt("working_minutes")),
                        Duration.ofMinutes(rs.getInt("break_minutes")),
                        Duration.ofMinutes(rs.getInt("base_minutes")),
                        Duration.ofMinutes(rs.getInt("overtime_within_statutory_minutes")),
                        Duration.ofMinutes(rs.getInt("overtime_beyond_statutory_minutes")),
                        Duration.ofMinutes(rs.getInt("night_minutes")),
                        Duration.ofMinutes(rs.getInt("legal_holiday_minutes"))),
                employeeId.value(), period.from(), period.toExclusive());

        return rows.stream().map(row -> new DailyAttendance(row.workDate, row.dayType,
                row.system, slicesOf(row.id), row.working, row.breakTime, row.base,
                row.within, row.beyond, row.night, row.legalHoliday)).toList();
    }

    private List<WorkSlice> slicesOf(UUID attendanceId) {
        return jdbc.query("""
                SELECT started_at, ended_at, premiums
                  FROM daily_attendance_slices
                 WHERE daily_attendance_id = ?
                 ORDER BY sequence_no
                """,
                (rs, rowNum) -> new WorkSlice(
                        new TimeRange(
                                BusinessZone.toLocal(rs.getObject("started_at",
                                        OffsetDateTime.class)),
                                BusinessZone.toLocal(rs.getObject("ended_at",
                                        OffsetDateTime.class))),
                        toPremiums((String[]) rs.getArray("premiums").getArray())),
                attendanceId);
    }

    private static Set<PremiumType> toPremiums(String[] values) {
        Set<PremiumType> premiums = EnumSet.noneOf(PremiumType.class);
        for (String value : values) {
            premiums.add(PremiumType.valueOf(value));
        }
        return premiums;
    }

    private static String toArrayLiteral(Set<PremiumType> premiums) {
        List<String> names = new ArrayList<>();
        premiums.forEach(premium -> names.add(premium.name()));
        return "{" + String.join(",", names) + "}";
    }

    /** 労働時間は 1 分単位で丸めを行わない（BR-01）ので、分への変換は情報を落とさない。 */
    private static int minutes(Duration duration) {
        return Math.toIntExact(duration.toMinutes());
    }

    private record Row(UUID id, LocalDate workDate, DayType dayType,
                       WorkingTimeSystemType system, Duration working, Duration breakTime,
                       Duration base, Duration within, Duration beyond, Duration night,
                       Duration legalHoliday) {
    }
}
