package jp.co.sample.kintai.attendance.infrastructure;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.shared.domain.CalculatedWorkDates;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * {@link CalculatedWorkDates} の実装。
 *
 * <p>日次勤怠を持つ {@code attendance} が提供する（ADR 0004）。
 * ポート越しに答えるので、{@code approval} は日次勤怠の構造を知らずに済む。
 */
@Repository
class CalculatedWorkDatesAdapter implements CalculatedWorkDates {

    private final DailyAttendanceRepository dailyAttendances;

    CalculatedWorkDatesAdapter(DailyAttendanceRepository dailyAttendances) {
        this.dailyAttendances = dailyAttendances;
    }

    @Override
    public Set<LocalDate> of(EmployeeId employeeId, DateRange period) {
        return dailyAttendances.findByPeriod(employeeId, period).stream()
                .map(DailyAttendance::workDate)
                .collect(Collectors.toUnmodifiableSet());
    }
}
