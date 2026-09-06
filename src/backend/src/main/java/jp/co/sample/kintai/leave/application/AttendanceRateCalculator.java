package jp.co.sample.kintai.leave.application;

import java.time.Duration;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.leave.domain.AttendanceRate;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.DayType;

/**
 * 出勤率を数える（BR-14・労基法 39 条 1 項）。
 *
 * <p>その付与期間の<strong>全労働日</strong>に対する<strong>出勤日</strong>の割合。
 *
 * <p><strong>判定そのものは {@link AttendanceRate} が持つ。</strong>
 * ここは事実（日数）を数えるだけで、8 割の閾値には触れない。
 */
final class AttendanceRateCalculator {

    private final CompanyCalendar calendar;

    AttendanceRateCalculator(CompanyCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * 算定期間の出勤率。
     *
     * <table>
     *   <caption>数え方（BR-14）</caption>
     *   <tr><th>区分</th><th>全労働日</th><th>出勤日</th></tr>
     *   <tr><td>所定労働日に労働時間があった</td><td>○</td><td>○</td></tr>
     *   <tr><td>所定労働日に労働時間が無い（欠勤）</td><td>○</td><td>×</td></tr>
     *   <tr><td>年次有給休暇を取得した</td><td>○</td><td>○（39 条 10 項）</td></tr>
     *   <tr><td>所定休日・法定休日</td><td>×</td><td>×</td></tr>
     *   <tr><td><strong>在籍していない日</strong></td><td><strong>×</strong></td><td>×</td></tr>
     * </table>
     *
     * @param days      算定期間の日次勤怠
     * @param leaveDays 算定期間に取得した承認済みの年休の日
     */
    AttendanceRate of(Employee employee, DateRange period, List<DailyAttendance> days,
                      Set<LocalDate> leaveDays) {
        Set<LocalDate> worked = days.stream()
                .filter(day -> day.workingTime().compareTo(Duration.ZERO) > 0)
                .map(DailyAttendance::workDate)
                .collect(Collectors.toUnmodifiableSet());

        int total = 0;
        int attended = 0;
        for (LocalDate date : period.dates().toList()) {
            // ★ 在籍していない日は分母に入れない。
            //   付与の対象を「付与日に在籍している社員」に限っているので通常は起きないが、
            //   再判定（付与のあとに退職した社員）では起きる
            if (!employee.isActiveOn(date) || calendar.dayTypeOf(date) != DayType.WORKDAY) {
                continue;
            }
            total++;
            if (worked.contains(date) || leaveDays.contains(date)) {
                attended++;
            }
        }
        return AttendanceRate.of(total, attended);
    }
}
