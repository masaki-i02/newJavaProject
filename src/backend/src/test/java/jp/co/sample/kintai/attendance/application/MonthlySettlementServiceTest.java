package jp.co.sample.kintai.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;

import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlement;
import jp.co.sample.kintai.employee.domain.Assignment;
import jp.co.sample.kintai.employee.domain.AssignmentRepository;
import jp.co.sample.kintai.employee.domain.Department;
import jp.co.sample.kintai.employee.domain.DepartmentCode;
import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.employee.domain.DepartmentRepository;
import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.employee.domain.Managership;
import jp.co.sample.kintai.employee.domain.ManagershipRepository;
import jp.co.sample.kintai.leave.application.PaidLeaveRequestService;
import jp.co.sample.kintai.leave.domain.AttendanceRate;
import jp.co.sample.kintai.leave.domain.GrantDecision;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantId;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantRepository;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequest;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.IntegrationTestBase;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/**
 * 月次清算のユースケース。<strong>清算期間の切り出しに関わる観点</strong>を置く。
 *
 * <p>計算そのものは {@code MonthlySettlementCalculatorTest} がドメインに直接あてている。
 * ここで見るのは、暦月と在籍期間の交わりで期間を切ったうえで
 * 年休の日を数える経路である（BR-05 / BR-16）。
 */
@DisplayName("月次清算のユースケース")
class MonthlySettlementServiceTest extends IntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 4, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    /** 退職日（最終在籍日）。清算期間は 10/16 未満になる。 */
    private static final LocalDate RETIRED = LocalDate.of(2026, 10, 15);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(TODAY.atTime(10, 0).atZone(BusinessZone.ID).toInstant(),
                BusinessZone.ID);
    }

    @Autowired
    private MonthlySettlementService settlements;
    @Autowired
    private PaidLeaveRequestService leaveRequests;
    @Autowired
    private PaidLeaveGrantRepository grants;
    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private DepartmentRepository departments;
    @Autowired
    private AssignmentRepository assignments;
    @Autowired
    private ManagershipRepository managerships;
    @Autowired
    private WorkRuleSeriesRepository series;
    @Autowired
    private WorkRuleRepository workRules;
    @Autowired
    private CompanyCalendarRepository calendarRepository;

    private EmployeeId yamadaId;
    private Requester yamada;
    private Requester manager;

    @BeforeEach
    void setUpOrganization() {
        yamadaId = hire("E0001", "山田 太郎");
        EmployeeId managerId = hire("E0100", "課長 次郎");
        yamada = new Requester(yamadaId, Set.of(Role.EMPLOYEE));
        manager = new Requester(managerId, Set.of(Role.EMPLOYEE, Role.APPROVER));

        var sales = new DepartmentId(UUID.randomUUID());
        departments.save(Department.root(sales, new DepartmentCode("SALES"), "営業部"));
        assignments.save(Assignment.startingAt(yamadaId, sales, HIRED));
        assignments.save(Assignment.startingAt(managerId, sales, HIRED));
        managerships.save(Managership.startingAt(sales, managerId, HIRED));

        var standard = new WorkRuleSeriesId(UUID.randomUUID());
        series.save(WorkRuleSeries.active(standard, "標準勤務"));
        workRules.save(WorkRules.versionOf(standard, HIRED, WorkRules.fixed(),
                Duration.ofHours(8), NightWindow.STANDARD));
        series.assign(yamadaId, standard, HIRED);

        for (LocalDate date = OCTOBER.atDay(1);
                date.isBefore(OCTOBER.plusMonths(1).atDay(1)); date = date.plusDays(1)) {
            switch (date.getDayOfWeek()) {
                case SUNDAY -> calendarRepository.save(date, DayType.LEGAL_HOLIDAY, "法定休日");
                case SATURDAY -> calendarRepository.save(date, DayType.NON_LEGAL_HOLIDAY,
                        "所定休日");
                default -> { }
            }
        }
        grants.save(new PaidLeaveGrant(PaidLeaveGrantId.generate(), yamadaId, 0,
                LocalDate.of(2026, 10, 1), AttendanceRate.of(120, 120),
                new GrantDecision.Granted(10), LocalDate.of(2026, 10, 1).atStartOfDay(), 1L));
    }

    /**
     * 在籍期間の外にある年休の日を、所定総から引かない（BR-05）。
     *
     * <p>10/20 の年休は<strong>在籍中に正当に承認されたもの</strong>である。
     * そのあとで 10/15 付けの退職が登録されると、承認済みの行はそのまま残り、
     * 清算期間だけが {@code [10/01, 10/16)} に縮む。
     * 暦月で数えると、<strong>働く義務がそもそも無い日</strong>を所定総から引くことになり、
     * 不足時間が 8 時間ぶん過少に出る。
     *
     * <p>期間の内側にある 10/05 の年休は引く。
     * <strong>片側だけを見ると、絞りを消しても気づけない</strong>（落とし穴 24）。
     */
    @Test
    @DisplayName("UT-LV-62 在籍期間の外にある年休の日は所定総から引かない")
    void leaveOutsideServicePeriodIsNotDeducted() {
        approveLeaveOn(LocalDate.of(2026, 10, 5));
        approveLeaveOn(LocalDate.of(2026, 10, 20));
        retire();

        MonthlySettlement settlement = settlements.settle(yamadaId, OCTOBER);

        // ★ 期待値を本番の関数から作らない。10/01〜10/15 の平日は 11 日（10/01 は木曜）。
        //   workdayCountIn を使うと、そちらが壊れたときに両辺が一緒に動く
        assertThat(settlement.period().period())
                .isEqualTo(new DateRange(OCTOBER.atDay(1), RETIRED.plusDays(1)));
        assertThat(settlement.paidLeaveDays())
                .as("引くのは期間の内側にある 10/05 の 1 日だけ")
                .isEqualTo(1);
        assertThat(settlement.scheduledTotalTime())
                .as("所定労働日 11 日 − 年休 1 日 = 10 日ぶん")
                .isEqualTo(Duration.ofHours(80));
    }

    /**
     * 承認後にカレンダーで休日へ変えられた年休の日を、所定総から引かない（BR-05 / BR-07）。
     *
     * <p>会社カレンダーは全社で 1 つしか無く、締めていない月なら人事が変更できる。
     * 年休を承認したあとにその日を休日へ変えると、
     * <strong>所定労働日数より年休の日数が多くなりうる。</strong>
     * 負の所定総は保存できないので、業務エラーですらない 500 になる（落とし穴 81 と同型）。
     *
     * <p>数える対象を {@code WORKDAY} に絞れば、この経路そのものが生まれない。
     */
    @Test
    @DisplayName("UT-LV-70 承認後に休日へ変えられた年休の日は所定総から引かない")
    void leaveOnADayTurnedIntoHolidayIsNotDeducted() {
        LocalDate leaveDate = LocalDate.of(2026, 10, 5);
        approveLeaveOn(leaveDate);
        calendarRepository.save(leaveDate, DayType.NON_LEGAL_HOLIDAY, "所定休日");

        MonthlySettlement settlement = settlements.settle(yamadaId, OCTOBER);

        // 10 月の平日は 22 日。うち 10/05 を所定休日へ変えたので所定労働日は 21 日
        assertThat(settlement.paidLeaveDays())
                .as("所定労働日でなくなった日は数えない")
                .isZero();
        assertThat(settlement.scheduledTotalTime())
                .as("所定労働日 21 日ぶん。年休の 1 日は引かない")
                .isEqualTo(Duration.ofHours(168));
    }

    private void approveLeaveOn(LocalDate leaveDate) {
        PaidLeaveRequest request = leaveRequests.submit(yamada, yamadaId, leaveDate,
                Optional.empty());
        leaveRequests.approve(manager, request.id(), request.version());
    }

    /** 退職を登録する。所属と部署長を閉じるのは別のユースケースの責務である。 */
    private void retire() {
        Employee employee = employees.findById(yamadaId).orElseThrow();
        employees.save(employee.retire(RETIRED));
    }

    private EmployeeId hire(String number, String name) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(number.toLowerCase() + "@example.com"), HIRED,
                Optional.empty(), Set.of(Role.EMPLOYEE)));
        return id;
    }
}
