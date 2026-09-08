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
import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlyDayCounts;
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
    private jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlementRepository
            settlementRepository;
    @Autowired
    private PaidLeaveRequestService leaveRequests;
    @Autowired
    private TimeClockService timeClocks;
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

    /**
     * <strong>欠勤日数を引き算で導かない。</strong>
     *
     * <p>年休を承認した日に出勤した月（落とし穴 97）では、
     * その日が<strong>年休の日数にも実労働のある日にも数えられる</strong>。
     * {@code 所定労働日数 − 年休 − 出勤} で求めると同じ日を 2 回引くので、
     * 欠勤日数が 1 日少なく出る。{@code Math.max(0, ...)} は
     * 負にならないようにするだけで、この重なりを取り除かない。
     *
     * <p>⑨ 欠勤日数は欠勤控除の直接の入力なので、控除漏れが静かに起こる。
     */
    @Test
    @DisplayName("UT-PAY-29 年休の日に出勤しても欠勤日数は二重に引かれない")
    void absentDaysAreCountedNotSubtracted() {
        LocalDate leaveDate = LocalDate.of(2026, 10, 5);   // 月曜
        approveLeaveOn(leaveDate);
        workOn(leaveDate);

        MonthlyDayCounts counts = settlements.dayCountsIn(yamadaId, OCTOBER);

        // 10 月の平日は 22 日。働いたのは 10/05 の 1 日だけで、それは年休の日でもある
        assertThat(counts.scheduledDays()).isEqualTo(22);
        assertThat(counts.paidLeaveDays()).isEqualTo(1);
        assertThat(counts.attendedDays()).isEqualTo(1);
        assertThat(counts.absentDays())
                .as("年休でも出勤でもない所定労働日は 21 日。引き算だと 20 日になる")
                .isEqualTo(21);
    }

    /**
     * <strong>日額の分母は暦月の所定労働日数である</strong>（労基法 24 条）。
     *
     * <p>清算期間（暦月 ∩ 在籍期間）のほうを分母にすると、
     * 月中退職の月の 1 日あたりの控除が 2 倍になる。
     * しかも暦月の所定労働日数は他のどの項目からも復元できないので、両方を持つ。
     */
    @Test
    @DisplayName("UT-PAY-30 月中退職の月は暦月と清算期間で所定労働日数が違う")
    void monthlyAndSettlementScheduledDaysDiffer() {
        retire();

        MonthlyDayCounts counts = settlements.dayCountsIn(yamadaId, OCTOBER);

        assertThat(counts.monthlyScheduledDays()).as("10 月の平日").isEqualTo(22);
        assertThat(counts.scheduledDays()).as("10/01〜10/15 の平日").isEqualTo(11);
        assertThat(counts.absentDays()).as("1 日も働いていない").isEqualTo(11);
    }

    /**
     * <strong>過去月を計算し直したら、同じ年度の後続月も計算し直す。</strong>
     *
     * <p>年度累計は行に焼き付けてある（{@code annualUsedBefore}）。
     * 過去月が動いたのに後続月をそのままにすると、
     * 36 条 4 項の年 360 時間の判定が<strong>過少なまま残り続ける</strong>。
     * 10 月が訂正で増えても 11 月は古い累計を握り続けるので、
     * 年度の途中で上限を超えても超過として現れない。
     *
     * <p>設計書は「過去月を再計算したとき、同一年度の後続月 → システム（連鎖して実行）」と
     * 書いていたが、実装も検査も無かった（落とし穴 155）。
     */
    @Test
    @DisplayName("UT-BR12-12 過去月を計算し直すと、同じ年度の後続月の年度累計も追随する")
    void recalculationCascadesToLaterMonthsInTheSameFiscalYear() {
        YearMonth november = OCTOBER.plusMonths(1);
        for (LocalDate date = november.atDay(1);
                date.isBefore(november.plusMonths(1).atDay(1)); date = date.plusDays(1)) {
            switch (date.getDayOfWeek()) {
                case SUNDAY -> calendarRepository.save(date, DayType.LEGAL_HOLIDAY, "法定休日");
                case SATURDAY -> calendarRepository.save(date, DayType.NON_LEGAL_HOLIDAY,
                        "所定休日");
                default -> calendarRepository.save(date, DayType.WORKDAY, "所定労働日");
            }
        }

        overtimeOn(LocalDate.of(2026, 10, 5));
        workOn(LocalDate.of(2026, 11, 4));

        Duration before = settlementRepository.find(yamadaId, november).orElseThrow()
                .agreementUsage().annualUsedBefore();

        // ★ 10 月に残業をもう 1 日足す。打刻の登録が 10 月を計算し直す
        overtimeOn(LocalDate.of(2026, 10, 6));

        Duration after = settlementRepository.find(yamadaId, november).orElseThrow()
                .agreementUsage().annualUsedBefore();

        assertThat(after)
                .as("10 月が増えたぶん、11 月が握る年度累計も増える")
                .isGreaterThan(before);
    }

    /** 本人として 9:00–22:00（休憩 1 時間）を打刻する。時間外 4 時間。 */
    private void overtimeOn(LocalDate date) {
        timeClocks.punch(yamada, yamadaId, TimeClockEvent.Type.CLOCK_IN,
                Optional.of(date.atTime(9, 0)));
        timeClocks.punch(yamada, yamadaId, TimeClockEvent.Type.BREAK_START,
                Optional.of(date.atTime(12, 0)));
        timeClocks.punch(yamada, yamadaId, TimeClockEvent.Type.BREAK_END,
                Optional.of(date.atTime(13, 0)));
        timeClocks.punch(yamada, yamadaId, TimeClockEvent.Type.CLOCK_OUT,
                Optional.of(date.atTime(22, 0)));
    }

    /** 本人として 9:00–18:00（休憩 1 時間）を打刻する。 */
    private void workOn(LocalDate date) {
        timeClocks.punch(yamada, yamadaId, TimeClockEvent.Type.CLOCK_IN,
                Optional.of(date.atTime(9, 0)));
        timeClocks.punch(yamada, yamadaId, TimeClockEvent.Type.BREAK_START,
                Optional.of(date.atTime(12, 0)));
        timeClocks.punch(yamada, yamadaId, TimeClockEvent.Type.BREAK_END,
                Optional.of(date.atTime(13, 0)));
        timeClocks.punch(yamada, yamadaId, TimeClockEvent.Type.CLOCK_OUT,
                Optional.of(date.atTime(18, 0)));
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
