package jp.co.sample.kintai.attendance.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 日次勤怠の参照。
 *
 * <p>{@code readOnly} なトランザクション境界をこの層に置く（アーキテクチャ設計書 6.1）。
 */
@Service
@Transactional(readOnly = true)
public class AttendanceQueryService {

    private final DailyAttendanceRepository dailyAttendances;

    public AttendanceQueryService(DailyAttendanceRepository dailyAttendances) {
        this.dailyAttendances = dailyAttendances;
    }

    public Optional<DailyAttendance> find(EmployeeId employeeId, LocalDate workDate) {
        return dailyAttendances.find(employeeId, workDate);
    }

    /**
     * その月の日次勤怠。
     *
     * <p>期間を<strong>暦月の半開区間</strong>で組み立てる。
     * {@code atEndOfMonth()} を上限にすると月末日が漏れる。
     */
    public List<DailyAttendance> findByMonth(EmployeeId employeeId, YearMonth month) {
        return dailyAttendances.findByPeriod(employeeId,
                new DateRange(month.atDay(1), month.plusMonths(1).atDay(1)));
    }
}
