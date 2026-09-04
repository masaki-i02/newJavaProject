package jp.co.sample.kintai.attendance.application;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlement;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlementCalculator;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlementRepository;
import jp.co.sample.kintai.attendance.domain.monthly.WeeklyOvertimeRule;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.SettlementPeriod;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;

/**
 * 月次清算のユースケース（BR-04 / BR-05 / BR-12）。
 *
 * <p>集める順序そのものが業務上の判断である。
 * <strong>日次勤怠は対象月ではなく週次判定の走査範囲で読む。</strong>
 * 対象月の中だけを読むと、月初の週に必要な前月の日が欠け、
 * 週 40 時間超と法定休日からの通算を取りこぼす。
 */
@Service
public class MonthlySettlementService {

    private final DailyAttendanceRepository dailyAttendances;
    private final MonthlySettlementRepository settlements;
    private final WorkRuleRepository workRules;
    private final EmployeeRepository employees;
    private final CompanyCalendar calendar;

    public MonthlySettlementService(DailyAttendanceRepository dailyAttendances,
                                    MonthlySettlementRepository settlements,
                                    WorkRuleRepository workRules,
                                    EmployeeRepository employees,
                                    CompanyCalendar calendar) {
        this.dailyAttendances = dailyAttendances;
        this.settlements = settlements;
        this.workRules = workRules;
        this.employees = employees;
        this.calendar = calendar;
    }

    /**
     * 清算して保存する。
     *
     * <p>適用する就業規則は<strong>清算期間の末日時点の版</strong>を使う。
     * 月中に改定された場合の日ごとの切り替えは未実装である（UT-BR05-19）。
     */
    @Transactional
    public MonthlySettlement settle(EmployeeId employeeId, YearMonth month) {
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));

        SettlementPeriod period = SettlementPeriod.of(month, employee.activePeriod())
                .orElseThrow(() -> new NotEmployedInMonthException(employeeId, month));

        LocalDate lastDay = period.period().toExclusive().minusDays(1);
        WorkRule workRule = workRules.findEffective(employeeId, lastDay)
                .orElseThrow(() -> new WorkRuleNotAssignedException(employeeId, lastDay));

        DateRange scanRange = WeeklyOvertimeRule.scanRangeFor(period.period());
        List<DailyAttendance> days = dailyAttendances.findByPeriod(employeeId, scanRange);
        Duration annualBefore = settlements.annualSubjectTimeBefore(employeeId, month);

        MonthlySettlement settlement = new MonthlySettlementCalculator(calendar)
                .calculate(employeeId, period, days, workRule, annualBefore);
        settlements.save(settlement);
        return settlement;
    }

    @Transactional(readOnly = true)
    public Optional<MonthlySettlement> find(EmployeeId employeeId, YearMonth month) {
        return settlements.find(employeeId, month);
    }

    /** 社員が見つからない。 */
    public static final class EmployeeNotFoundException extends DomainException {

        private static final long serialVersionUID = 1L;

        EmployeeNotFoundException(EmployeeId employeeId) {
            super("社員が見つかりません: " + employeeId.value());
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:employee-not-found";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.NOT_FOUND;
        }

        @Override
        public String title() {
            return "社員が見つかりません";
        }
    }

    /**
     * その月に 1 日も在籍していない。
     *
     * <p>入社前・退職後の月がこれにあたる。<strong>実装の不備ではないので業務エラーで返す。</strong>
     */
    public static final class NotEmployedInMonthException extends DomainException {

        private static final long serialVersionUID = 1L;

        NotEmployedInMonthException(EmployeeId employeeId, YearMonth month) {
            super("対象月に在籍していません: 社員 %s / 対象月 %s"
                    .formatted(employeeId.value(), month));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:not-employed-in-month";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "対象月に在籍していません";
        }
    }

    /** 就業規則が適用されていない。人事が適用を登録するまで清算できない。 */
    public static final class WorkRuleNotAssignedException extends DomainException {

        private static final long serialVersionUID = 1L;

        WorkRuleNotAssignedException(EmployeeId employeeId, LocalDate date) {
            super("就業規則が適用されていません: 社員 %s / 基準日 %s"
                    .formatted(employeeId.value(), date));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:work-rule-not-assigned";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "就業規則が適用されていません";
        }
    }
}
